package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in output collection, not semantic acceptance. Existing fluency controls stay unchanged. */
@RunWith(AndroidJUnit4::class)
class TranslationQualityCorpusDiagnosticTest {
    @Test fun collectOneAuthoredSourceAndBothTargets() = coreScreenSmoke("translation-quality-corpus") {
        val arguments = InstrumentationRegistry.getArguments()
        val caseId = requireNotNull(arguments.getString("translation_quality_case")) {
            "Choose one explicit authored source with translation_quality_case."
        }
        val mode = requireNotNull(arguments.getString("translation_quality_refinement")) {
            "Choose draft_only or captured explicitly."
        }
        TranslationQualityCorpusConsumer.collect(context, instrumentation.context, caseId, mode)
    }
}
