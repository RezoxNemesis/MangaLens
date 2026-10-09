package com.mangalens.core.adblock

/** Retains the existing entry point while sharing one media-safe guard and document lifecycle. */
class EnterpriseAdBlockEngine(private val base: AdBlockEngine = AdBlockEngine()) : AdBlockWebViewClient(base) {
    fun mutationObserverScript(): String = AdBlockScript.build()
}
