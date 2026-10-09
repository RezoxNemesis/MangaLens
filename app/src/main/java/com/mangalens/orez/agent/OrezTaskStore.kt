package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import org.json.JSONArray
import org.json.JSONObject
import com.mangalens.orez.OrezRoute
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.SubtitleRefinementPin

/**
 * Durable journal for autonomous Orez work.
 *
 * Plans are checkpointed before execution so process death or navigation does not
 * erase the user's objective. The JSON is intentionally schema-versioned so future
 * Orez releases can migrate richer task graphs without coupling Room columns to
 * every tool argument.
 */
class OrezTaskStore(
    private val dao: OrezTaskDao
) {
    suspend fun load(id: String): OrezTaskPlan? = dao.get(id)?.let { entity ->
        runCatching { decode(entity.planJson) }.getOrNull()
    }

    internal fun decode(encoded: String): OrezTaskPlan {
        require(encoded.length <= 128 * 1024) { "Orez journal exceeds the safe limit" }
        val root = JSONObject(encoded)
        val schema = root.getInt("schema")
        require(schema in 1..3) { "Unsupported Orez task schema" }
        val steps = root.getJSONArray("steps")
        require(steps.length() in 1..32) { "Invalid task step count" }
        return OrezTaskPlan(
            id = root.getString("id"),
            objective = root.getString("objective"),
            status = OrezTaskStatus.valueOf(root.getString("status")),
            createdAt = root.getLong("createdAt"),
            authorization = root.optJSONObject("authorization")?.let { scope ->
                OrezTaskAuthorization(OrezTrustOrigin.valueOf(scope.getString("origin")), scope.getBoolean("explicitUserRequest"),
                    strings(scope.getJSONArray("chapterIds")).toSet(), strings(scope.getJSONArray("urls")).toSet(),
                    scope.optJSONObject("translation")?.let { options ->
                        OrezTranslationOptions(options.getString("targetLanguage"), options.getString("styleId"),
                            options.getString("customStyle"), options.getString("ocrScript"), options.getBoolean("highAccuracy"),
                            options.getBoolean("preserveStyle"), options.getBoolean("localRefinement"))
                    },
                    scope.optJSONObject("selectedMedia")?.let { source -> selection(source, schema) },
                    scope.optJSONObject("subtitle")?.let { options -> subtitle(options, schema) })
            },
            executionEpoch = root.optLong("executionEpoch", 0),
            pausedByUser = root.optBoolean("pausedByUser", false),
            resuming = root.optBoolean("resuming", false),
            pendingControl = root.optString("pendingControl").takeIf { it.isNotBlank() && it != "null" }?.let(OrezPendingControl::valueOf),
            steps = (0 until steps.length()).map { i ->
                val step = steps.getJSONObject(i)
                val args = step.getJSONObject("arguments")
                OrezPlanStep(
                    index = step.getInt("index"),
                    outputs = step.optJSONObject("outputs")?.let(::stringMap).orEmpty(),
                    dependsOn = step.optJSONArray("dependsOn")?.let { values ->
                        (0 until values.length()).map { values.getInt(it) }.toSet()
                    }.orEmpty(),
                    references = step.optJSONObject("references")?.let { values ->
                        values.keys().asSequence().associateWith { key ->
                            val reference = values.getJSONObject(key)
                            OrezOutputReference(reference.getInt("stepIndex"), OrezOutputField.valueOf(reference.getString("field")))
                        }
                    }.orEmpty(),
                    outputKind = step.optString("outputKind").takeIf { it.isNotBlank() && it != "null" }?.let(OrezOutputKind::valueOf),
                    status = OrezStepStatus.valueOf(step.getString("status")),
                    call = OrezToolCall(
                        name = step.getString("tool"),
                        capability = OrezCapability.valueOf(step.getString("capability")),
                        risk = OrezToolRisk.valueOf(step.getString("risk")),
                        summary = step.getString("summary"),
                        route = if (step.isNull("route")) null else OrezRoute.valueOf(step.getString("route")),
                        arguments = stringMap(args)
                    )
                )
            }
        )
    }
    suspend fun checkpoint(
        plan: OrezTaskPlan,
        status: OrezTaskStatus = plan.status,
        error: String? = null
    ): Boolean = journalLock.withLock {
        val current = load(plan.id)
        // WorkManager runs in the app process. This shared lock fences all store instances,
        // while the Room transaction keeps cancellation authoritative across restarts.
        if (current != null && (current.executionEpoch != plan.executionEpoch || current.pausedByUser != plan.pausedByUser ||
                current.resuming != plan.resuming || current.pendingControl != plan.pendingControl ||
                current.authorization != plan.authorization || current.objective != plan.objective ||
                current.steps.map { Triple(it.call, it.dependsOn, it.references) } != plan.steps.map { Triple(it.call, it.dependsOn, it.references) })) {
            return@withLock false
        }
        if (current != null && ((current.status == OrezTaskStatus.COMPLETED && status !in setOf(OrezTaskStatus.COMPLETED, OrezTaskStatus.CANCELLED)) ||
                current.steps.any { verified -> verified.status == OrezStepStatus.COMPLETED && plan.steps.getOrNull(verified.index)?.let {
                    it.status != OrezStepStatus.COMPLETED || it.outputs != verified.outputs || it.outputKind != verified.outputKind
                } != false })) return@withLock false
        persist(plan.copy(status = status), error)
    }

    suspend fun isExecuting(id: String, epoch: Long): Boolean = load(id)?.let {
        it.executionEpoch == epoch && !it.pausedByUser && !it.resuming && it.pendingControl == null && it.status == OrezTaskStatus.RUNNING
    } == true

    suspend fun pause(id: String): OrezTaskPlan? = journalLock.withLock {
        val plan = load(id) ?: return@withLock null
        if (plan.status !in setOf(OrezTaskStatus.PLANNED, OrezTaskStatus.RUNNING, OrezTaskStatus.WAITING)) return@withLock null
        if (plan.pausedByUser && !plan.resuming) return@withLock plan
        val paused = plan.copy(status = OrezTaskStatus.WAITING, pausedByUser = true, resuming = false, executionEpoch = plan.executionEpoch + 1)
        if (persist(paused, "Paused by you. Resume to continue.")) paused else null
    }

    suspend fun resume(id: String, dispatchReady: Boolean = true): OrezTaskPlan? = journalLock.withLock {
        val plan = load(id) ?: return@withLock null
        if (plan.pendingControl != null || plan.status !in setOf(OrezTaskStatus.WAITING, OrezTaskStatus.FAILED)) return@withLock null
        OrezDurablePlanRules.validate(plan)
        val resumed = plan.copy(status = if (dispatchReady) OrezTaskStatus.PLANNED else OrezTaskStatus.WAITING,
            pausedByUser = !dispatchReady, resuming = !dispatchReady, executionEpoch = plan.executionEpoch + 1,
            steps = plan.steps.map { if (it.status in setOf(OrezStepStatus.FAILED, OrezStepStatus.BLOCKED))
                it.copy(status = OrezStepStatus.PENDING) else it })
        if (persist(resumed)) resumed else null
    }

    /** Publish the new native generation and dispatch eligibility in one journal commit. */
    suspend fun finishResume(plan: OrezTaskPlan, failure: String? = null): OrezTaskPlan? = journalLock.withLock {
        val latest = load(plan.id) ?: return@withLock null
        if (!latest.resuming || latest.executionEpoch != plan.executionEpoch || latest.authorization != plan.authorization ||
            latest.steps.map { Triple(it.call, it.dependsOn, it.references) } != plan.steps.map { Triple(it.call, it.dependsOn, it.references) } ||
            latest.steps.any { verified -> verified.status == OrezStepStatus.COMPLETED && plan.steps[verified.index] != verified }) return@withLock null
        val ready = plan.copy(status = if (failure == null) OrezTaskStatus.PLANNED else OrezTaskStatus.FAILED,
            pausedByUser = false, resuming = false)
        if (persist(ready, failure)) ready else null
    }

    /** A stop intent remains visible and replayable until its native generation is stopped. */
    suspend fun beginStop(id: String, control: OrezPendingControl, restoreOnly: Boolean = false): OrezTaskPlan? = journalLock.withLock {
        val plan = load(id) ?: return@withLock null
        if (restoreOnly) return@withLock plan.takeIf { it.pendingControl == control }
        if (plan.status in setOf(OrezTaskStatus.COMPLETED, OrezTaskStatus.CANCELLED, OrezTaskStatus.DISPATCHED)) return@withLock null
        if (plan.pendingControl == control) return@withLock plan
        if (control == OrezPendingControl.PAUSE && (plan.pendingControl == OrezPendingControl.CANCEL ||
                plan.status == OrezTaskStatus.FAILED || (plan.pausedByUser && !plan.resuming))) return@withLock null
        val pending = plan.copy(status = OrezTaskStatus.WAITING, pausedByUser = true, pendingControl = control,
            executionEpoch = plan.executionEpoch + 1)
        if (persist(pending, if (control == OrezPendingControl.CANCEL) "Finishing native cancellation." else "Finishing native pause.")) pending else null
    }

    suspend fun finishStop(plan: OrezTaskPlan): OrezTaskPlan? = journalLock.withLock {
        val latest = load(plan.id) ?: return@withLock null
        if (latest.executionEpoch != plan.executionEpoch || latest.pendingControl == null || latest.pendingControl != plan.pendingControl ||
            latest.authorization != plan.authorization || latest.steps.map { Triple(it.call, it.dependsOn, it.references) } !=
            plan.steps.map { Triple(it.call, it.dependsOn, it.references) }) return@withLock null
        val stopped = plan.copy(status = if (plan.pendingControl == OrezPendingControl.CANCEL) OrezTaskStatus.CANCELLED else OrezTaskStatus.WAITING,
            pendingControl = null, resuming = false)
        if (persist(stopped, if (stopped.status == OrezTaskStatus.WAITING) "Paused by you. Resume to continue." else null)) stopped else null
    }

    suspend fun noteControlFailure(plan: OrezTaskPlan, error: String) = journalLock.withLock {
        val latest = load(plan.id) ?: return@withLock false
        if (latest.executionEpoch != plan.executionEpoch || (latest.pendingControl == null && !latest.resuming)) return@withLock false
        persist(latest, error.take(300))
    }

    suspend fun cancel(id: String): OrezTaskPlan? = journalLock.withLock {
        val plan = load(id) ?: return@withLock null
        if (plan.status in setOf(OrezTaskStatus.COMPLETED, OrezTaskStatus.DISPATCHED)) return@withLock null
        val cancelled = plan.copy(status = OrezTaskStatus.CANCELLED, resuming = false, pendingControl = null, executionEpoch = plan.executionEpoch + 1)
        if (persist(cancelled)) cancelled else null
    }

    private suspend fun persist(plan: OrezTaskPlan, error: String? = null): Boolean {
        val encoded = encode(plan)
        require(encoded.length <= 128 * 1024) { "Orez journal exceeds the safe limit" }
        return dao.checkpointIfNotCancelled(OrezTaskEntity(id = plan.id, objective = plan.objective,
            status = plan.status.name, planJson = encoded, createdAt = plan.createdAt,
            updatedAt = System.currentTimeMillis(), lastError = error))
    }

    suspend fun pruneFinished(retentionMs: Long = DEFAULT_RETENTION_MS) {
        dao.pruneFinished(System.currentTimeMillis() - retentionMs)
    }

    private fun encode(plan: OrezTaskPlan): String {
        val root = JSONObject()
            .put("schema", if (plan.authorization?.let { it.selectedMedia != null || it.subtitle != null } == true) 3 else
                if (plan.authorization != null || plan.executionEpoch != 0L || plan.pausedByUser || plan.resuming || plan.pendingControl != null ||
                plan.steps.any { it.dependsOn.isNotEmpty() || it.references.isNotEmpty() || it.outputKind != null }) 2 else 1)
            .put("id", plan.id)
            .put("objective", plan.objective)
            .put("status", plan.status.name)
            .put("createdAt", plan.createdAt)
            .put("executionEpoch", plan.executionEpoch).put("pausedByUser", plan.pausedByUser).put("resuming", plan.resuming)
            .put("pendingControl", plan.pendingControl?.name ?: JSONObject.NULL)
        plan.authorization?.let { scope ->
            val authorization = JSONObject().put("origin", scope.origin.name).put("explicitUserRequest", scope.explicitUserRequest)
                .put("chapterIds", JSONArray(scope.chapterIds.toList())).put("urls", JSONArray(scope.urls.toList()))
            scope.translation?.let { options -> authorization.put("translation", JSONObject()
                .put("targetLanguage", options.targetLanguage).put("styleId", options.styleId).put("customStyle", options.customStyle)
                .put("ocrScript", options.ocrScript).put("highAccuracy", options.highAccuracy)
                .put("preserveStyle", options.preserveStyle).put("localRefinement", options.localRefinement)) }
            scope.selectedMedia?.let { source -> authorization.put("selectedMedia", JSONObject()
                .put("uri", source.uri).put("cacheKey", source.cacheKey).put("label", source.label).put("headers", JSONObject(source.headers))
                .put("resolutionId", source.resolutionId ?: JSONObject.NULL).put("expectedDurationUs", source.expectedDurationUs ?: JSONObject.NULL)
                .put("audio", source.audio?.let { audio -> JSONObject().put("uri", audio.uri).put("resolutionId", audio.resolutionId)
                    .put("headers", JSONObject(audio.headers)) } ?: JSONObject.NULL)) }
            scope.subtitle?.let { options -> authorization.put("subtitle", JSONObject()
                .put("sourceLanguage", options.sourceLanguage).put("targetLanguage", options.targetLanguage).put("style", options.style)
                .put("windowSeconds", options.windowSeconds).put("overlapSeconds", options.overlapSeconds).put("threads", options.threads)
                .put("outputMode", options.outputMode.name).put("pipeline", options.pipeline.name).put("customStyle", options.customStyle)
                .put("localRefinement", options.localRefinement).put("translationPolicy", options.translationPolicy)
                .put("capturedStyle", options.capturedStyle?.let { profile -> JSONObject().put("id", profile.id).put("name", profile.name)
                    .put("instruction", profile.instruction).put("preserveHonorifics", profile.preserveHonorifics)
                    .put("preserveNames", profile.preserveNames).put("naturalDialogue", profile.naturalDialogue) } ?: JSONObject.NULL)
                .put("refinementPin", options.refinementPin?.let { pin -> JSONObject().put("modelId", pin.modelId)
                    .put("sha256", pin.sha256).put("bytes", pin.bytes) } ?: JSONObject.NULL)) }
            root.put("authorization", authorization)
        }

        val steps = JSONArray()
        plan.steps.forEach { step ->
            val args = JSONObject()
            step.call.arguments.forEach { (key, value) -> args.put(key, value) }

            steps.put(
                JSONObject()
                    .put("index", step.index)
                    .put("status", step.status.name)
                    .put("tool", step.call.name)
                    .put("capability", step.call.capability.name)
                    .put("risk", step.call.risk.name)
                    .put("summary", step.call.summary)
                    .put("route", step.call.route?.name ?: JSONObject.NULL)
                    .put("arguments", args)
                    .put("outputs", JSONObject(step.outputs))
                    .put("dependsOn", JSONArray(step.dependsOn.toList()))
                    .put("references", JSONObject().apply { step.references.forEach { (key, reference) ->
                        put(key, JSONObject().put("stepIndex", reference.stepIndex).put("field", reference.field.name))
                    } })
                    .put("outputKind", step.outputKind?.name ?: JSONObject.NULL)
            )
        }
        root.put("steps", steps)
        return root.toString()
    }

    companion object {
        private val journalLock = Mutex()
        private const val DEFAULT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        private fun selection(source: JSONObject, schema: Int): OrezMediaSelection {
            val legacy = OrezMediaSelection(source.getString("uri"), source.getString("cacheKey"), source.getString("label"), stringMap(source.getJSONObject("headers")))
            if (schema <= 2) return legacy
            require(listOf("resolutionId", "audio", "expectedDurationUs").all(source::has)) { "Captured playable source metadata is incomplete." }
            return legacy.copy(resolutionId = if (source.isNull("resolutionId")) null else source.getString("resolutionId"),
                expectedDurationUs = if (source.isNull("expectedDurationUs")) null else source.getLong("expectedDurationUs"),
                audio = if (source.isNull("audio")) null else source.getJSONObject("audio").let { audio ->
                    OrezAudioSelection(audio.getString("uri"), audio.getString("resolutionId"), stringMap(audio.getJSONObject("headers"))) })
        }
        private fun subtitle(options: JSONObject, schema: Int): OrezSubtitleOptions {
            val legacy = OrezSubtitleOptions(options.getString("sourceLanguage"), options.getString("targetLanguage"), options.getString("style"),
                options.getInt("windowSeconds"), options.getInt("overlapSeconds"), options.getInt("threads"))
            if (schema <= 2) return legacy
            require(listOf("outputMode", "pipeline", "customStyle", "localRefinement", "translationPolicy", "capturedStyle", "refinementPin").all(options::has)) {
                "Captured subtitle policy metadata is incomplete."
            }
            return legacy.copy(outputMode = SubtitleOutputMode.valueOf(options.getString("outputMode")), pipeline = SubtitlePipeline.valueOf(options.getString("pipeline")),
                customStyle = options.getString("customStyle"), localRefinement = options.getBoolean("localRefinement"), translationPolicy = options.getString("translationPolicy"),
                capturedStyle = if (options.isNull("capturedStyle")) null else options.getJSONObject("capturedStyle").let { profile ->
                    TranslationStyleProfile(profile.getString("id"), profile.getString("name"), profile.getString("instruction"),
                        profile.getBoolean("preserveHonorifics"), profile.getBoolean("preserveNames"), profile.getBoolean("naturalDialogue")) },
                refinementPin = if (options.isNull("refinementPin")) null else options.getJSONObject("refinementPin").let { pin ->
                    SubtitleRefinementPin(pin.getString("modelId"), pin.getString("sha256"), pin.getLong("bytes")) })
        }
        private fun stringMap(values: JSONObject): Map<String, String> = values.keys().asSequence().associateWith {
            require(values.get(it) is String) { "Journal arguments and outputs must be strings." }
            values.getString(it)
        }
        private fun strings(values: JSONArray) = (0 until values.length()).map {
            require(values.get(it) is String) { "Journal scope must contain strings." }; values.getString(it)
        }
    }
}

