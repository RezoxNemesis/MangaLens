package com.mangalens.core.translation.inpainting

import java.io.IOException
import java.net.URI
import java.net.InetAddress
import java.util.Locale
import com.mangalens.orez.research.OrezResearchPublicNetworkPolicy

/** Fixed public HTTPS publisher flow; no cookies, auth, model selection or URI hints. */
internal object LaMaModelDownloadPolicy {
    fun url(value: String): URI = URI(value).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null && it.port in setOf(-1, 443))
        require(value.length in 1..16_384 && value.none(Char::isISOControl))
        val host = it.host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        require('%' !in host)
        if (':' in host || host.matches(Regex("[0-9.]+"))) require(OrezResearchPublicNetworkPolicy.isPublic(InetAddress.getByName(host)))
        // Fixed publisher and its public content-delivery domains, not arbitrary redirect hints.
        require(host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co"))

    }
    fun responseOffset(code: Int, previous: Long, length: Long, range: String?): Long {
        require(previous in 0..LaMaReconstructionPin.MODEL_BYTES.toLong())
        return when (code) {
            200 -> { if (length >= 0 && length != LaMaReconstructionPin.MODEL_BYTES.toLong()) throw IOException("The publisher response has an unexpected model size."); 0 }
            206 -> {
                val pieces = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.orEmpty())?.groupValues ?: throw IOException("The model resume range is missing.")
                val start = pieces[1].toLongOrNull(); val end = pieces[2].toLongOrNull(); val total = pieces[3].toLongOrNull()
                require(start == previous && end == LaMaReconstructionPin.MODEL_BYTES.toLong() - 1 && total == LaMaReconstructionPin.MODEL_BYTES.toLong()) {
                    "The model resume response does not match its pinned bytes."
                }
                if (length >= 0 && length != LaMaReconstructionPin.MODEL_BYTES.toLong() - previous) throw IOException("The model resume response stopped early.")
                previous
            }
            else -> throw IOException("The model source returned HTTP $code. Try the download again later.")
        }
    }
}
