package com.mangalens.core.translation

/**
 * Text plus the Hindi intermediate used for its exact Roman rendering, when present.
 * Evidence is revalidated at every persistence boundary; it is never a trusted flag.
 */
data class TranslationDraft(val text: String, val hindiDraft: String? = null)
