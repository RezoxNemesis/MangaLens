package com.mangalens.ui.web

internal enum class BrowserProfileKind { NORMAL, PRIVATE, WORK, CUSTOM }
internal data class BrowserProfileChoice(val kind: BrowserProfileKind, val token: String, val label: String) {
    val key: String get() = when (kind) {
        BrowserProfileKind.NORMAL -> "normal"
        BrowserProfileKind.WORK -> "work"
        BrowserProfileKind.PRIVATE -> "private_$token"
        BrowserProfileKind.CUSTOM -> "custom_$token"
    }
    val nativeName: String get() = when (kind) {
        BrowserProfileKind.NORMAL -> "Default"
        BrowserProfileKind.WORK -> "mangalens_work_v1"
        BrowserProfileKind.PRIVATE -> "mangalens_private_v1_$token"
        BrowserProfileKind.CUSTOM -> "mangalens_custom_v1_$token"
    }
    val allowsNativeSourceHandoff: Boolean get() = kind == BrowserProfileKind.NORMAL
    val ephemeral: Boolean get() = kind == BrowserProfileKind.PRIVATE
    fun validate(): BrowserProfileChoice {
        require(label.length in 1..40 && label.none(Char::isISOControl))
        require(if (kind in setOf(BrowserProfileKind.PRIVATE, BrowserProfileKind.CUSTOM)) token.matches(Regex("[a-f0-9]{32}")) else token.isEmpty())
        require(if (kind == BrowserProfileKind.NORMAL) label == "Normal" else if (kind == BrowserProfileKind.WORK) label == "Work" else true)
        return this
    }
    companion object {
        val Normal = BrowserProfileChoice(BrowserProfileKind.NORMAL, "", "Normal")
        val Work = BrowserProfileChoice(BrowserProfileKind.WORK, "", "Work")
        fun privateSession() = BrowserProfileChoice(BrowserProfileKind.PRIVATE, browserId(), "Private").validate()
    }
}
internal object BrowserProfilePolicy {
    const val MAX_CUSTOM = 4
    const val MAX_BINDINGS = 16
    const val CATALOG_BYTES = 8_192
    const val HANDOFF_MESSAGE = "Native Reader, Video, downloads and source captions are unavailable from this isolated profile. Open the source separately in Normal to use those features."
    fun validKey(value: String) = value in setOf("normal", "work") || Regex("(?:private|custom)_[a-f0-9]{32}").matches(value)
    fun ownedPrivateName(value: String) = Regex("mangalens_private_v1_[a-f0-9]{32}").matches(value)
    fun workspaceDirectory(choice: BrowserProfileChoice): String {
        choice.validate(); require(!choice.ephemeral)
        return if (choice.kind == BrowserProfileKind.NORMAL) "browser_workspace" else "browser_profiles/${choice.key}"
    }
}
/** A retired owner can never be revived by selecting the same named profile again. */
internal class BrowserProfileOwner(val choice: BrowserProfileChoice) {
    @Volatile private var current = true
    fun retire() { current = false }
    fun isCurrent() = current
}
