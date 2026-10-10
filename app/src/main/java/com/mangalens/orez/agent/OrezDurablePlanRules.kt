package com.mangalens.orez.agent

import com.mangalens.ui.video.ProviderCaptionReceipt
import com.mangalens.ui.video.fingerprint
import com.mangalens.download.ProviderCaptionKind
import com.mangalens.download.ProviderCaptionFormat

/** Bounded, sequential DAG: edges only point to earlier verified steps. No route is an effect receipt. */
object OrezDurablePlanRules {
    const val MAX_STEPS = 8
    private val chapterTools = setOf("search_saved_memory", "inspect_saved_chapter", "translate_saved_chapter")
    private val mediaTools = setOf("inspect_selected_media", "inspect_downloaded_media", "generate_subtitles")
    private val acquisitionTools = setOf("save_next_chapter", "save_chapter_url")
    private val libraryTools = OrezLibraryRequest.tools
    private val nativeTools = chapterTools + mediaTools + acquisitionTools + libraryTools
    private val supportedTools = nativeTools + setOf("enqueue_download", "research_web")

    fun supports(plan: OrezTaskPlan) = plan.steps.isNotEmpty() && plan.steps.all { it.call.name in supportedTools }
    fun requiresNetwork(plan: OrezTaskPlan) = plan.steps.any { it.call.name in setOf("enqueue_download", "research_web", "save_next_chapter", "save_chapter_url") } ||
        (plan.steps.any { it.call.name in mediaTools } && plan.authorization?.selectedMedia?.let { media ->
            listOfNotNull(media.uri, media.audio?.uri).any { it.startsWith("https://", true) || it.startsWith("http://", true) }
        } == true)
    fun outputKind(tool: String) = when (tool) {
        "enqueue_download" -> OrezOutputKind.DOWNLOAD_RECEIPT
        "research_web" -> OrezOutputKind.RESEARCH_EVIDENCE
        in libraryTools -> OrezOutputKind.LIBRARY_METADATA
        "search_saved_memory" -> OrezOutputKind.MEMORY_SEARCH
        "save_next_chapter", "save_chapter_url" -> OrezOutputKind.ACQUIRED_CHAPTER
        "inspect_saved_chapter" -> OrezOutputKind.SAVED_CHAPTER
        "translate_saved_chapter" -> OrezOutputKind.CHAPTER_TRANSLATION
        "inspect_selected_media", "inspect_downloaded_media" -> OrezOutputKind.MEDIA_SOURCE
        "generate_subtitles" -> OrezOutputKind.SUBTITLE_TRACK
        else -> error("Unsupported durable tool")
    }

    fun validate(plan: OrezTaskPlan) {
        require(plan.objective.length <= 16_384) { "Task request is too long." }
        require(plan.id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid task identity" }
        require(plan.executionEpoch >= 0) { "Invalid execution generation" }
        require(plan.steps.size in 1..MAX_STEPS) { "Orez accepts up to $MAX_STEPS steps in one task." }
        require(plan.steps.map { it.index } == plan.steps.indices.toList()) { "Task steps must have ordered unique indices." }
        require(supports(plan)) { "This tool has no durable native executor. Navigation is a handoff only." }
        val authorization = plan.authorization
        if (plan.steps.any { it.call.name in nativeTools || it.call.name == "research_web" }) {
            require(authorization != null && authorization.explicitUserRequest && authorization.origin == OrezTrustOrigin.USER) {
                "Native work requires captured, explicit user scope."
            }
        }
        if (plan.steps.any { it.call.name in chapterTools }) {
            requireNotNull(authorization)
            require(authorization.chapterIds.isNotEmpty() && authorization.chapterIds.size <= MAX_STEPS &&
                authorization.chapterIds.all { it.matches(Regex("[a-f0-9]{32}")) }) { "Invalid selected chapter scope" }
            authorization.translation?.let(::validateOptions)
        }
        if (plan.steps.any { it.call.name in mediaTools }) {
            val scope = requireNotNull(authorization)
            validateSubtitleOptions(requireNotNull(scope.subtitle) { "Subtitle settings were not captured." })
            scope.selectedMedia?.let(::validateSelection)
        }
        if (plan.steps.any { it.call.name in libraryTools }) OrezLibraryRules.validate(plan)
        plan.steps.forEach { step ->
            OrezToolRegistry().validate(step.call)
            require(step.dependsOn.all { it in 0 until step.index }) { "Dependencies must point to earlier steps; cycles are not executable." }
            require(step.references.keys.none { it in step.call.arguments }) { "An argument cannot be both literal and a typed reference." }
            step.references.forEach { (argument, reference) ->
                require(argument == reference.field.key && reference.stepIndex in step.dependsOn) { "Undeclared or mismatched output reference" }
                require(outputKind(plan.steps[reference.stepIndex].call.name) in referenceKinds(reference.field)) { "This reference has another native output type." }
            }
            when (step.call.name) {
                "save_next_chapter", "save_chapter_url" -> {
                    require(plan.steps.size == 1 && step.index == 0 && step.dependsOn.isEmpty() && step.references.isEmpty()) { "Acquisition authorizes one bounded chapter only." }
                    val scope = requireNotNull(authorization?.chapterAcquisition) { "The public chapter acquisition scope was not captured." }.validated()
                    if (step.call.name == "save_next_chapter") {
                        val next = requireNotNull(scope.next)
                        require(OrezNextChapterRequest.isRequested(plan.objective) && step.call.arguments.getValue("chapterId") == next.chapterId &&
                            next.chapterId in authorization!!.chapterIds) { "Next chapter requires the literal user request and its captured saved source." }
                    } else require(scope.next == null && OrezNextChapterRequest.directUrl(plan.objective) == scope.targetUrl &&
                        step.call.arguments.getValue("value") == scope.targetUrl) { "The chapter URL differs from the explicit user request." }
                }
                "research_web" -> {
                    require(plan.steps.size == 1 && step.index == 0) { "Research does not authorize additional application tools." }
                    require(step.references.isEmpty() && step.dependsOn.isEmpty()) { "Research accepts one explicit user question." }
                    com.mangalens.orez.research.OrezResearchRequest.captured(plan.objective, step.call.arguments)
                }
                "enqueue_download" -> {
                    require(step.references.isEmpty()) { "Downloads need explicit scoped URLs." }
                    if (authorization != null) require(step.call.arguments["value"] in authorization.urls) { "Download URL exceeds captured user scope." }
                }
                "search_saved_memory" -> require(step.references.isEmpty() && step.call.arguments["chapterId"] in authorization!!.chapterIds) { "Memory search exceeds selected chapter scope." }
                "inspect_saved_chapter" -> {
                    require(step.references.isEmpty() && step.call.arguments["chapterId"] in authorization!!.chapterIds) {
                        "Chapter inspection exceeds the selected user scope."
                    }
                }
                "translate_saved_chapter" -> {
                    require(step.references.keys == setOf("chapterId", "sourceFingerprint") &&
                        step.references.values.map { it.stepIndex }.distinct().size == 1) { "Translation requires one typed, inspected chapter source." }
                    require(authorization!!.translation != null && step.call.arguments["targetLanguage"] == authorization.translation.targetLanguage) {
                        "Translation settings differ from the captured request."
                    }
                }
                "inspect_selected_media" -> require(step.references.isEmpty() &&
                    step.call.arguments["sourceId"] == authorization!!.selectedMedia?.sourceId) { "Media inspection exceeds the explicit selected source." }
                "inspect_downloaded_media" -> require(step.call.arguments.isEmpty() && step.references.keys == setOf("downloadId") &&
                    step.references.getValue("downloadId").field == OrezOutputField.DOWNLOAD_ID) { "Media inspection needs this plan's typed completed download." }
                "generate_subtitles" -> {
                    val producer = step.references["sourceId"]?.stepIndex?.let { plan.steps.getOrNull(it) }
                    val captionScope = producer?.call?.name == "inspect_selected_media" && authorization?.selectedMedia?.providerCaptions != null
                    val expectedReferences = setOf("sourceId", "sourceFingerprint", "speechModelSha256") +
                        if (captionScope) setOf("captionInventorySha256") else emptySet()
                    require(step.references.keys == expectedReferences &&
                        step.references.values.map { it.stepIndex }.distinct().size == 1 &&
                        outputKind(plan.steps[step.references.getValue("sourceId").stepIndex].call.name) == OrezOutputKind.MEDIA_SOURCE) {
                        "Subtitle generation requires one typed inspected media/model source."
                    }
                    require(step.call.arguments.keys == setOf("targetLanguage") && step.call.arguments["targetLanguage"] == authorization!!.subtitle!!.targetLanguage) {
                        "Subtitle settings differ from the captured user request."
                    }
                }
            }
        }
        val completed = plan.steps.takeWhile { it.status == OrezStepStatus.COMPLETED }.size
        require(plan.steps.drop(completed).none { it.status == OrezStepStatus.COMPLETED }) { "Completed steps must form a prefix." }
        plan.steps.take(completed).forEach { step ->
            require(step.outputKind == outputKind(step.call.name) ||
                (step.call.name == "enqueue_download" && step.outputKind == null)) { "Completed output has the wrong type." }
            validateReceipt(plan, resolve(plan, step), step.outputs, completed = true)
        }
    }

    private fun referenceKinds(field: OrezOutputField) = when (field) {
        OrezOutputField.CHAPTER_ID -> setOf(OrezOutputKind.SAVED_CHAPTER)
        OrezOutputField.SOURCE_FINGERPRINT -> setOf(OrezOutputKind.SAVED_CHAPTER, OrezOutputKind.MEDIA_SOURCE)
        OrezOutputField.DOWNLOAD_ID -> setOf(OrezOutputKind.DOWNLOAD_RECEIPT)
        OrezOutputField.MEDIA_SOURCE_ID, OrezOutputField.SPEECH_MODEL_SHA256, OrezOutputField.CAPTION_INVENTORY_SHA256 -> setOf(OrezOutputKind.MEDIA_SOURCE)
    }

    private fun validateSubtitleOptions(options: OrezSubtitleOptions) {
        OrezSubtitleContract.validate(options)
    }

    private fun validateSelection(source: OrezMediaSelection) {
        source.validateCapturedSource()
    }

    private fun validateOptions(options: OrezTranslationOptions) {
        require(options.targetLanguage in setOf("hi", "hi-latn", "en", "ja", "ko", "zh", "fr", "es", "de")) { "Unsupported translation target" }
        require(options.styleId in setOf("natural", "faithful", "casual", "formal", "manga", "webtoon", "literal", "custom")) { "Unsupported translation style" }
        require(options.customStyle.length <= 1200 && options.ocrScript in setOf("AUTO", "LATIN", "DEVANAGARI", "CHINESE", "JAPANESE", "KOREAN")) {
            "Invalid captured translation options"
        }
        options.nativeChapterConfig()
    }

    fun resolve(plan: OrezTaskPlan, step: OrezPlanStep): OrezPlanStep {
        require(step.dependsOn.all { plan.steps[it].status == OrezStepStatus.COMPLETED }) { "A dependency is not verified yet." }
        val arguments = step.references.mapValues { (_, reference) ->
            val producer = plan.steps[reference.stepIndex]
            require(producer.outputKind in referenceKinds(reference.field)) { "A typed native receipt is missing." }
            requireNotNull(producer.outputs[reference.field.key]) { "Referenced output is missing." }
        }
        return step.copy(call = step.call.copy(arguments = step.call.arguments + arguments))
    }

    fun validateReceipt(plan: OrezTaskPlan, resolved: OrezPlanStep, outputs: Map<String, String>, completed: Boolean) {
        require(outputs.size <= 40 && outputs.all { it.key.length <= 80 && it.value.length <= 8192 }) { "Tool receipt exceeds its safe limit." }
        val id = requestId(plan.id, resolved.index)
        if (resolved.call.name == "enqueue_download") {
            if (completed) {
                require(outputs["downloadId"] == id) { "Tool result belongs to another transfer." }
                require(!outputs["destination"].isNullOrBlank()) { "Download completion requires a published destination." }
            }
            return
        }
        require(outputs["requestId"] == id) { "Tool result belongs to another request." }
        if (resolved.call.name in libraryTools) {
            OrezLibraryRules.receipt(plan,resolved,outputs,completed)
            return
        }
        if (resolved.call.name in acquisitionTools) {
            require(completed) { "Acquisition completion requires a saved native receipt." }
            val scope = requireNotNull(plan.authorization?.chapterAcquisition).validated()
            require(outputs.keys == setOf("requestId", "ownerRequestId", "chapterId", "title", "sourceFingerprint", "pageCount", "originalBytes", "acquisitionScopeFingerprint", "targetUrlSha256", "status", "destination")) { "Unexpected acquisition receipt fields." }
            val chapterId = com.mangalens.core.reader.ChapterLibrary.id(scope.targetUrl)
            require(outputs["ownerRequestId"] == id && outputs["chapterId"] == chapterId && outputs["destination"] == "library:$chapterId" &&
                outputs["status"] == "COMPLETED" && outputs["acquisitionScopeFingerprint"] == scope.fingerprint &&
                outputs["targetUrlSha256"] == OrezNextChapterPolicy.sha(scope.targetUrl) && outputs["sourceFingerprint"]?.matches(Regex("[a-f0-9]{64}")) == true &&
                outputs["pageCount"]?.toIntOrNull()?.let { it in 1..OrezNextChapterPolicy.MAX_PAGES } == true &&
                outputs["originalBytes"]?.toLongOrNull()?.let { it in 1..OrezNextChapterPolicy.MAX_CHAPTER_BYTES } == true &&
                outputs["title"]?.length?.let { it <= 250 } == true) { "Acquisition receipt changed its native owner, target or actual saved original proof." }
            return
        }
        if (resolved.call.name == "research_web") {
            require(completed) { "Research does not publish an unfinished citation receipt." }
            val request = com.mangalens.orez.research.OrezResearchRequest.captured(plan.objective, resolved.call.arguments)
            com.mangalens.orez.research.OrezResearchEvidenceCodec.receipt(outputs, id, request)
            return
        }
        if (resolved.call.name == "search_saved_memory") {
            require(outputs.keys == setOf("requestId", "chapterId", "sourceFingerprint", "associationRevision", "memoryHitCount", "memoryHits", "memoryIncomplete")) { "Unexpected memory receipt fields." }
            require(outputs["chapterId"] == resolved.call.arguments["chapterId"] && outputs["chapterId"] in plan.authorization!!.chapterIds &&
                outputs["sourceFingerprint"]?.matches(Regex("[a-f0-9]{64}")) == true &&
                outputs["associationRevision"]?.toLongOrNull()?.let { it >= 0 } == true && outputs["memoryIncomplete"] in setOf("true", "false")) { "Memory result changed selected chapter scope." }
            val rows = org.json.JSONArray(outputs.getValue("memoryHits"))
            val limit = resolved.call.arguments["limit"]?.toIntOrNull() ?: 8
            require(rows.length() in 0..limit && outputs["memoryHitCount"]?.toIntOrNull() == rows.length()) { "Memory match count exceeds the bounded selected request." }
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == setOf("chapterId", "pageIndex", "sourceSha256", "imageWidth", "imageHeight", "bounds", "targetLanguage", "revision", "kind", "text"))
                require(row.getString("chapterId") == outputs["chapterId"] && row.getInt("pageIndex") in 0 until 2000 &&
                    row.getString("sourceSha256").matches(Regex("[a-f0-9]{64}")) && row.getString("targetLanguage").matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")) &&
                    row.getInt("revision") >= 0 && row.getString("text").isNotBlank() && row.getString("text").length <= 512 &&
                    row.getString("kind") in com.mangalens.core.translation.memory.MemorySearchKind.entries.map { it.name })
                val bounds = row.getJSONArray("bounds"); require(bounds.length() == 4)
                com.mangalens.core.translation.memory.MemoryRegionBounds(bounds.getInt(0), bounds.getInt(1), bounds.getInt(2), bounds.getInt(3))
                    .validate(row.getInt("imageWidth"), row.getInt("imageHeight"))
            }
            return
        }

        if (resolved.call.name in mediaTools) {
            validateMediaReceipt(plan, resolved, outputs, completed)
            return
        }
        require(outputs["chapterId"] == resolved.call.arguments["chapterId"] &&
            outputs["chapterId"] in plan.authorization!!.chapterIds) { "Tool result belongs to another chapter." }
        require(outputs["sourceFingerprint"]?.matches(Regex("[a-f0-9]{64}")) == true) { "Chapter source evidence is missing." }
        require(outputs["pageCount"]?.toIntOrNull()?.let { it in 1..1000 } == true) { "Chapter page evidence is missing." }
        if (resolved.call.name == "translate_saved_chapter") {
            require(outputs["sourceFingerprint"] == resolved.call.arguments["sourceFingerprint"]) { "Chapter sources changed after inspection." }
            val inspected = plan.steps[resolved.references.getValue("chapterId").stepIndex]
            require(outputs["pageCount"] == inspected.outputs["pageCount"]) { "Translation receipt changed the inspected page scope." }
            require(outputs["translationTaskId"]?.matches(Regex("[a-f0-9]{32}")) == true &&
                outputs["generation"]?.matches(Regex("[a-f0-9]{32}")) == true) { "Native translation identity is missing." }
            require(outputs["targetLanguage"] == plan.authorization.translation!!.targetLanguage) { "Translation result has another target language." }
            val version = plan.authorization.translation.reconstructionVersion
            require(outputs["reconstructionVersion"] == version.toString() || version == 1 && outputs["reconstructionVersion"].isNullOrEmpty()) { "Native receipt has another captured reconstruction version." }
            val refinement = plan.authorization.translation.refinementRequestFingerprint()
            if (refinement != null) require(outputs["refinementRequestFingerprint"] == refinement) { "Native receipt has another captured refinement request." }
            else require(outputs["refinementRequestFingerprint"].isNullOrEmpty()) { "A legacy request cannot claim a captured refinement model." }
            if (completed) {
                require(outputs["status"] == "COMPLETED" && outputs["completedPages"] == outputs["pageCount"] &&
                    outputs["destination"] == "chapter-translation:${outputs["translationTaskId"]}") {
                    "Translation completion requires verified saved output for every scoped page."
                }
            }
        }
    }

    private fun validateMediaReceipt(plan: OrezTaskPlan, resolved: OrezPlanStep, outputs: Map<String, String>, completed: Boolean) {
        val sourceId = when (resolved.call.name) {
            "inspect_downloaded_media" -> "download-${resolved.call.arguments.getValue("downloadId")}".also {
                val download = plan.steps[resolved.references.getValue("downloadId").stepIndex]
                require(download.outputs["downloadId"] == requestId(plan.id, download.index) && download.outputs["storage"] == "published-file" &&
                    download.outputs["bytes"]?.toLongOrNull()?.let { bytes -> bytes > 0 } == true) {
                    "Subtitle generation requires this plan's verified published media file. Adaptive cache is unsupported."
                }
            }
            else -> resolved.call.arguments.getValue("sourceId")
        }
        val descriptor = plan.authorization?.selectedMedia?.takeIf { it.sourceId == sourceId }
        val inventory = descriptor?.providerCaptions
        val captionPin = inventory?.fingerprint()
        require(outputs["captionInventorySha256"] == captionPin) { "The provider caption inventory differs from captured source scope." }
        require(outputs["sourceId"] == sourceId && outputs["sourceFingerprint"]?.matches(Regex("[a-f0-9]{64}")) == true &&
            (outputs["speechModelSha256"]?.matches(Regex("[a-f0-9]{64}")) == true || outputs["speechModelSha256"] == "" &&
                descriptor?.hasProviderCaptionCandidate() == true && plan.authorization?.subtitle?.pipeline == com.mangalens.ui.video.SubtitlePipeline.SOURCE_TRANSLATION)) {
            "Verified source/model or captured provider inventory evidence is missing."
        }
        if (resolved.call.name != "generate_subtitles") return
        val scope = requireNotNull(plan.authorization?.subtitle)
        val id = requestId(plan.id, resolved.index)
        require(outputs["sourceFingerprint"] == resolved.call.arguments["sourceFingerprint"] &&
            outputs["speechModelSha256"] == resolved.call.arguments["speechModelSha256"] &&
            outputs["captionInventorySha256"] == resolved.call.arguments["captionInventorySha256"] &&
            outputs["ownerRequestId"] == id && outputs["subtitleTaskId"]?.matches(Regex("[a-f0-9]{32}")) == true &&
            outputs["generation"]?.matches(Regex("[a-f0-9]{32}")) == true && outputs["targetLanguage"] == scope.targetLanguage &&
            outputs["sourceLanguage"] == scope.sourceLanguage) { "Subtitle receipt exceeds captured native source/configuration/owner scope." }
        val fullEvidence = setOf("configFingerprint", "pipeline", "outputMode", "translationPolicy", "audioComplete", "sourceCueCount", "pendingTargetCues")
        val oldLegacy = fullEvidence.none { it in outputs } && OrezSubtitleContract.isLegacy(scope) &&
            plan.authorization.selectedMedia?.hasExtendedScope() != true
        if (!oldLegacy) {
            require(outputs.keys.containsAll(fullEvidence) && outputs["configFingerprint"] == scope.nativeConfig(resolved.call.arguments.getValue("speechModelSha256")).fingerprint() &&
                outputs["pipeline"] == scope.pipeline.name && outputs["outputMode"] == scope.outputMode.name && outputs["translationPolicy"] == scope.translationPolicy &&
                outputs["audioComplete"] in setOf("true", "false") && outputs["sourceCueCount"]?.toIntOrNull()?.let { it in 0..30_000 } == true &&
                outputs["pendingTargetCues"]?.toIntOrNull()?.let { it in 0..30_000 } == true) { "Subtitle receipt omitted or changed captured output policy evidence." }
        }
        if (completed) {
            val duration = outputs["durationMs"]?.toLongOrNull()
            val processed = outputs["processedMs"]?.toLongOrNull()
            require(outputs["status"] == "COMPLETED" && duration != null && duration in 0..21_600_000 &&
                processed != null && processed in 1..21_600_000 && (outputs["providerPayloadSha256"] != null || processed + 1500 >= duration) &&
                outputs["cueCount"]?.toIntOrNull()?.let { it in 1..30_000 } == true &&
                outputs["windowCount"]?.toIntOrNull()?.let { it in 1..4000 } == true &&
                outputs["srtSha256"]?.matches(Regex("[a-f0-9]{64}")) == true && outputs["vttSha256"]?.matches(Regex("[a-f0-9]{64}")) == true &&
                outputs["srtBytes"]?.toLongOrNull()?.let { it in 1..20_000_000 } == true &&
                outputs["vttBytes"]?.toLongOrNull()?.let { it in 1..20_000_000 } == true &&
                outputs["destination"] == "subtitle-track:${outputs["subtitleTaskId"]}") {
                "Subtitle completion requires a full verified native track and saved SRT/VTT receipts."
            }
            if (outputs["providerPayloadSha256"] != null) {
                val captured = requireNotNull(descriptor) { "Provider completion has no selected source scope." }
                val proof = ProviderCaptionReceipt(outputs.getValue("subtitleTaskId"), outputs.getValue("generation"),
                    outputs.getValue("sourceFingerprint"), outputs.getValue("configFingerprint"), requireNotNull(captionPin),
                    outputs.getValue("providerTrackSha256"), outputs.getValue("providerLanguage"), ProviderCaptionKind.valueOf(outputs.getValue("providerKind")),
                    ProviderCaptionFormat.valueOf(outputs.getValue("providerFormat")), outputs.getValue("providerPayloadSha256"),
                    outputs.getValue("providerCuesSha256"), outputs.getValue("sourceCueCount").toInt(), processed!!)
                OrezSubtitleTools.verifyCompleted(OrezSubtitleReceipt(proof.taskId, proof.generation, id,
                    OrezSubtitleSnapshot(sourceId, captured, proof.sourceFingerprint, outputs.getValue("speechModelSha256")), scope,
                    OrezNativeSubtitleStatus.COMPLETED, duration!!, processed, outputs.getValue("windowCount").toInt(), outputs.getValue("cueCount").toInt(),
                    OrezSubtitleExports(outputs.getValue("srtSha256"), outputs.getValue("vttSha256"), outputs.getValue("srtBytes").toLong(), outputs.getValue("vttBytes").toLong()),
                    configFingerprint = proof.configFingerprint, audioComplete = outputs["audioComplete"] == "true", sourceCueCount = proof.cueCount,
                    pendingTargetCues = outputs.getValue("pendingTargetCues").toInt(), providerCaptionReceipt = proof))
            } else {
            if (descriptor?.hasFragmentSourceCandidate() == true) require(outputs["fragmentContentSha256"]?.matches(Regex("[a-f0-9]{64}")) == true &&
                outputs["fragmentSize"]?.toLongOrNull()?.let { it in 1..4L * 1024 * 1024 * 1024 } == true) {
                "Fragment subtitle completion omitted the actual original audio byte receipt."
            }
            require(OrezSubtitleContract.isLegacy(scope) || outputs["audioComplete"] == "true" &&
                outputs["sourceCueCount"]?.toIntOrNull()?.let { it in 1..30_000 } == true && outputs["pendingTargetCues"] == "0") {
                "Subtitle completion requires finished original speech and every requested target cue."
            }
            descriptor?.verifySubtitleTail(duration!!, processed!!)
            }
        }
    }

    // Step zero retains the native identity used by schema-1 single-download journals.
    fun requestId(taskId: String, index: Int) = if (index == 0) "orez-$taskId" else "orez-$taskId-step-$index"
}
