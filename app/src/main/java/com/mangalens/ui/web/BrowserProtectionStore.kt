package com.mangalens.ui.web

import com.mangalens.core.adblock.AdBlockMode
import com.mangalens.core.adblock.AdBlockSite
import java.util.Collections

internal class BrowserProtectionSnapshot(modes: Map<String, AdBlockMode> = emptyMap()) {
    val modes: Map<String, AdBlockMode> = Collections.unmodifiableMap(modes.toMap())
    fun modeFor(url: String) = AdBlockSite.from(url)?.host?.let { modes[it] } ?: AdBlockMode.STANDARD
}

/** IO actor only. An acknowledged mode is backed by the exact successful private readback. */
internal class BrowserProtectionStore(private val io: BrowserWorkspaceIo) {
    private var value = io.read(MAX_BYTES)?.let(::decode) ?: BrowserProtectionSnapshot()
    fun snapshot() = value
    fun set(site: AdBlockSite, mode: AdBlockMode, current: () -> Boolean): BrowserProtectionSnapshot {
        check(current()) { "The page changed. Open its Protection Center again." }
        val next = value.modes.toMutableMap()
        if (mode == AdBlockMode.STANDARD) next.remove(site.host) else next[site.host] = mode
        require(next.size <= MAX_HOSTS) { "Site protection capacity reached. Restore an older host to Standard first." }
        val bytes = encode(next)
        io.write(bytes) // Production journal checks the current owner again at actual atomic promotion.
        val actual = requireNotNull(io.read(MAX_BYTES)) { "Protection settings readback is unavailable." }
        check(actual.contentEquals(bytes)) { "Protection settings changed before readback." }
        value = decode(actual)
        return value
    }
    companion object {
        const val MAX_HOSTS = 128
        const val MAX_BYTES = 48 * 1024
        private fun encode(modes: Map<String, AdBlockMode>): ByteArray =
            ("protection-v1\n" + modes.toSortedMap().entries.joinToString("") { "${it.key}\t${it.value.name}\n" }).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
        private fun decode(bytes: ByteArray): BrowserProtectionSnapshot {
            require(bytes.size <= MAX_BYTES)
            val text = bytes.toString(Charsets.UTF_8)
            require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes) && text.startsWith("protection-v1\n") && text.endsWith('\n'))
            val rows = text.removePrefix("protection-v1\n").split('\n').dropLast(1)
            require(rows.size <= MAX_HOSTS)
            val modes = linkedMapOf<String, AdBlockMode>()
            for (row in rows) {
                val fields = row.split('\t'); require(fields.size == 2)
                val host = fields[0]; require(AdBlockSite.from("https://$host/")?.host == host && host !in modes)
                val mode = AdBlockMode.valueOf(fields[1]); require(mode != AdBlockMode.STANDARD)
                modes[host] = mode
            }
            return BrowserProtectionSnapshot(modes)
        }
    }
}
