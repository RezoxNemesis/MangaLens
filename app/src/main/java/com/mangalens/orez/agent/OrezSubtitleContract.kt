package com.mangalens.orez.agent

import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitlePipeline

/** These mappings deliberately enumerate every native policy field, including immutable style/model evidence. */
internal fun OrezSubtitleOptions.nativeConfig(model: String) = SubtitleGenerationConfig(sourceLanguage, targetLanguage, style,
    model.takeIf { it.isNotEmpty() }, windowSeconds, overlapSeconds, threads, outputMode, pipeline, customStyle, localRefinement,
    translationPolicy, capturedStyle, refinementPin, sceneContext, refinementInputProfileRevision)

internal fun SubtitleGenerationConfig.orezOptions() = OrezSubtitleOptions(sourceLanguage, targetLanguage, style,
    windowSeconds, overlapSeconds, threads, outputMode, pipeline, customStyle, localRefinement,
    translationPolicy, capturedStyle, refinementPin, sceneContext, refinementInputProfileRevision)

internal object OrezSubtitleContract {
    fun isLegacy(options: OrezSubtitleOptions) = options.pipeline == SubtitlePipeline.WHISPER_ENGLISH &&
        options.targetLanguage == "en" && options.style == "whisper-english" && options.outputMode == SubtitleOutputMode.TRANSLATED &&
        options.customStyle.isEmpty() && !options.localRefinement && options.translationPolicy == "whisper-english-v1" &&
        options.capturedStyle == null && options.refinementPin == null && options.sceneContext.isEmpty() && options.refinementInputProfileRevision == null

    fun validate(options: OrezSubtitleOptions) {
        require(options == options.normalized() && (options.sourceLanguage == "auto" || options.sourceLanguage.matches(Regex("[a-z]{2,3}"))) &&
            options.windowSeconds == 8 && options.overlapSeconds == 1 && options.threads in 1..4) { "Invalid captured speech configuration." }
        if (options.pipeline == SubtitlePipeline.WHISPER_ENGLISH) {
            require(isLegacy(options)) { "Legacy Whisper requests produce translated English subtitles only." }
            return
        }
        require(options.targetLanguage in setOf("en", "hi", "hi-latn") && options.translationPolicy == "mlkit-dialogue-v1") {
            "Unsupported captured subtitle target or translation policy."
        }
        require(options.sceneContext.length <= 1200 && options.sceneContext.none { it == '\u0000' || it == '\r' })
        require(options.style in setOf("natural", "faithful", "casual", "formal", "manga", "webtoon", "literal", "custom") &&
            options.customStyle.length <= TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS) { "Unsupported captured subtitle style." }
        val profile = requireNotNull(options.capturedStyle) { "Capture the complete subtitle style before scheduling." }
        require(profile.id == options.style && profile.name.length in 1..100 && profile.instruction.length in 1..1200 &&
            (if (options.style == "custom") options.customStyle.isNotBlank() && profile.instruction == options.customStyle else options.customStyle.isEmpty())) {
            "The captured subtitle style profile differs from the requested style."
        }
        require(options.refinementInputProfileRevision == null || options.refinementInputProfileRevision.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "The captured localization input version is invalid."
        }
        if (!options.localRefinement) {
            require(options.refinementPin == null && options.refinementInputProfileRevision == null && options.style in setOf("natural", "faithful") &&
                profile == TranslationStyleProfile.fromId(options.style)) { "This subtitle style requires a captured verified local refinement model." }
        } else {
            val model = requireNotNull(options.refinementPin) { "Select a verified local refinement model before scheduling subtitles." }
            require(model.modelId.matches(Regex("[A-Za-z0-9._-]{1,100}")) && model.sha256.matches(Regex("[a-f0-9]{64}")) &&
                model.bytes in 1_000_000..4_000_000_000L) { "The captured subtitle refinement model is invalid." }
        }
    }
}
