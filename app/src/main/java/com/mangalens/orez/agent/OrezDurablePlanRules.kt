package com.mangalens.orez.agent

/** Bounded, sequential DAG: edges only point to earlier verified steps. No route is an effect receipt. */
object OrezDurablePlanRules {
    const val MAX_STEPS = 8
    private val chapterTools = setOf("inspect_saved_chapter", "translate_saved_chapter")
    private val mediaTools = setOf("inspect_selected_media", "inspect_downloaded_media", "generate_subtitles")
    private val nativeTools = chapterTools + mediaTools
    private val supportedTools = nativeTools + "enqueue_download"

    fun supports(plan: OrezTaskPlan) = plan.steps.isNotEmpty() && plan.steps.all { it.call.name in supportedTools }
    fun requiresNetwork(plan: OrezTaskPlan) = plan.steps.any { it.call.name == "enqueue_download" } ||
        (plan.steps.any { it.call.name in mediaTools } && plan.authorization?.selectedMedia?.uri?.let {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        } == true)
    fun outputKind(tool: String) = when (tool) {
        "enqueue_download" -> OrezOutputKind.DOWNLOAD_RECEIPT
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
        if (plan.steps.any { it.call.name in nativeTools }) {
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
        plan.steps.forEach { step ->
            OrezToolRegistry().validate(step.call)
            require(step.dependsOn.all { it in 0 until step.index }) { "Dependencies must point to earlier steps; cycles are not executable." }
            require(step.references.keys.none { it in step.call.arguments }) { "An argument cannot be both literal and a typed reference." }
            step.references.forEach { (argument, reference) ->
                require(argument == reference.field.key && reference.stepIndex in step.dependsOn) { "Undeclared or mismatched output reference" }
                require(outputKind(plan.steps[reference.stepIndex].call.name) in referenceKinds(reference.field)) { "This reference has another native output type." }
            }
            when (step.call.name) {
                "enqueue_download" -> {
                    require(step.references.isEmpty()) { "Downloads need explicit scoped URLs." }
                    if (authorization != null) require(step.call.arguments["value"] in authorization.urls) { "Download URL exceeds captured user scope." }
                }
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
                    require(step.references.keys == setOf("sourceId", "sourceFingerprint", "speechModelSha256") &&
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
        OrezOutputField.MEDIA_SOURCE_ID, OrezOutputField.SPEECH_MODEL_SHA256 -> setOf(OrezOutputKind.MEDIA_SOURCE)
    }

    private fun validateSubtitleOptions(options: OrezSubtitleOptions) {
        require(options == options.normalized() && options.targetLanguage == "en" && options.style == "whisper-english") {
            "This native speech provider generates English subtitles only."
        }
        require((options.sourceLanguage == "auto" || options.sourceLanguage.matches(Regex("[a-z]{2,3}"))) &&
            options.windowSeconds == 8 && options.overlapSeconds == 1 && options.threads in 1..4) { "Invalid captured speech configuration." }
    }

    private fun validateSelection(source: OrezMediaSelection) {
        require(source.uri.length in 1..16_000 && source.cacheKey.length in 1..16_000 && source.label.length <= 250 &&
            listOf(source.uri, source.cacheKey, source.label).none { it.any(Char::isISOControl) } &&
            runCatching { java.net.URI(source.uri).scheme?.lowercase() in setOf("http", "https", "content", "file", "android.resource") }.getOrDefault(false)) {
            "The explicit playable source is invalid."
        }
        require(source.headers.size <= 32 && source.headers.all { (key, value) -> key.length in 1..128 && value.length <= 8192 &&
            key.none(Char::isISOControl) && value.none(Char::isISOControl) }) { "Selected source headers exceed the captured scope limit." }
    }

    private fun validateOptions(options: OrezTranslationOptions) {
        require(options.targetLanguage in setOf("hi", "hi-latn", "en", "ja", "ko", "zh", "fr", "es", "de")) { "Unsupported translation target" }
        require(options.styleId in setOf("natural", "faithful", "casual", "formal", "webtoon", "custom")) { "Unsupported translation style" }
        require(options.customStyle.length <= 1200 && options.ocrScript in setOf("AUTO", "LATIN", "DEVANAGARI", "CHINESE", "JAPANESE", "KOREAN")) {
            "Invalid captured translation options"
        }
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
        require(outputs.size <= 20 && outputs.all { it.key.length <= 80 && it.value.length <= 8192 }) { "Tool receipt exceeds its safe limit." }
        val id = requestId(plan.id, resolved.index)
        if (resolved.call.name == "enqueue_download") {
            if (completed) {
                require(outputs["downloadId"] == id) { "Tool result belongs to another transfer." }
                require(!outputs["destination"].isNullOrBlank()) { "Download completion requires a published destination." }
            }
            return
        }
        require(outputs["requestId"] == id) { "Tool result belongs to another request." }
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
        require(outputs["sourceId"] == sourceId && outputs["sourceFingerprint"]?.matches(Regex("[a-f0-9]{64}")) == true &&
            outputs["speechModelSha256"]?.matches(Regex("[a-f0-9]{64}")) == true) { "Verified source/model evidence is missing." }
        if (resolved.call.name != "generate_subtitles") return
        val scope = requireNotNull(plan.authorization?.subtitle)
        val id = requestId(plan.id, resolved.index)
        require(outputs["sourceFingerprint"] == resolved.call.arguments["sourceFingerprint"] &&
            outputs["speechModelSha256"] == resolved.call.arguments["speechModelSha256"] &&
            outputs["ownerRequestId"] == id && outputs["subtitleTaskId"]?.matches(Regex("[a-f0-9]{32}")) == true &&
            outputs["generation"]?.matches(Regex("[a-f0-9]{32}")) == true && outputs["targetLanguage"] == scope.targetLanguage &&
            outputs["sourceLanguage"] == scope.sourceLanguage) { "Subtitle receipt exceeds captured native source/configuration/owner scope." }
        if (completed) {
            val duration = outputs["durationMs"]?.toLongOrNull()
            val processed = outputs["processedMs"]?.toLongOrNull()
            require(outputs["status"] == "COMPLETED" && duration != null && duration in 0..21_600_000 &&
                processed != null && processed in 1..21_600_000 && processed + 1500 >= duration &&
                outputs["cueCount"]?.toIntOrNull()?.let { it in 1..30_000 } == true &&
                outputs["windowCount"]?.toIntOrNull()?.let { it in 1..4000 } == true &&
                outputs["srtSha256"]?.matches(Regex("[a-f0-9]{64}")) == true && outputs["vttSha256"]?.matches(Regex("[a-f0-9]{64}")) == true &&
                outputs["srtBytes"]?.toLongOrNull()?.let { it in 1..20_000_000 } == true &&
                outputs["vttBytes"]?.toLongOrNull()?.let { it in 1..20_000_000 } == true &&
                outputs["destination"] == "subtitle-track:${outputs["subtitleTaskId"]}") {
                "Subtitle completion requires a full verified native track and saved SRT/VTT receipts."
            }
        }
    }

    // Step zero retains the native identity used by schema-1 single-download journals.
    fun requestId(taskId: String, index: Int) = if (index == 0) "orez-$taskId" else "orez-$taskId-step-$index"
}
