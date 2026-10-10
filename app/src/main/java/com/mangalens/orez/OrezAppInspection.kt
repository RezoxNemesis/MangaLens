package com.mangalens.orez

import java.util.Locale

/** Literal USER requests only. A quoted instruction or retrieved text is never a request. */
internal enum class OrezAppInspection(val toolName: String) {
    MODEL_STATUS("check_model_status"), APP_DIAGNOSTICS("inspect_app_diagnostics");

    companion object {
        fun parseExplicit(input: String): OrezAppInspection? {
            if (input.length !in 1..160 || input.any { it.isISOControl() }) return null
            val value = input.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim()
                .removePrefix("please ")
            return when (value) {
                "check model", "check models", "check local model", "check local models",
                "check model status", "show model status", "show local model status",
                "is the local model ready", "is my local model ready" -> MODEL_STATUS
                "inspect app diagnostics", "show app diagnostics", "check app diagnostics",
                "show mangalens diagnostics", "inspect mangalens diagnostics" -> APP_DIAGNOSTICS
                else -> null
            }
        }
    }
}

/** Scalar process observations, never raw errors, model paths or a receipt of fresh inference. */
internal data class OrezAppInspectionSnapshot(
    val applicationVersion: String,
    val sdk: Int,
    val supportedAbis: List<String>,
    val engineMode: String,
    val resourceMode: String,
    val model: OrezModelState
)

internal object OrezAppInspectionFormatter {
    fun describe(request: OrezAppInspection, snapshot: OrezAppInspectionSnapshot): String {
        val model = snapshot.model
        val status = buildString {
            append("Local model status: ")
            append(when {
                model.verifying -> "verification is in progress."
                model.ready -> "a process-verified model is available for the current runtime policy."
                model.installed -> "model data is installed, but a usable model is not currently ready."
                model.downloading -> "model download is in progress."
                else -> "no usable local model is currently ready."
            })
            append("\nSelected tier: ").append(model.selectedTier.displayName)
            append("; fallback candidate tier: ").append(model.activeTier?.displayName ?: "none")
            append("; installed tiers: ").append(model.installedTiers.sortedBy { it.ordinal }
                .joinToString { it.displayName }.ifBlank { "none" }).append('.')
            if (model.legacyInstalled) append(" A retained legacy install is also recorded.")
            if (model.downloading) {
                append("\nDownloaded: ").append(model.bytes.coerceAtLeast(0L)).append(" bytes")
                if (model.total > 0L) append(" of ").append(model.total)
                append("; this is transfer progress.")
            }
            if (model.error != null) append("\nModel setup reports an issue. Open Settings → Orez Models for its details and Recheck model health.")
            else append("\nOpen Settings → Orez Models to recheck health or choose a download.")
            append("\nThis is the current process status; a new checksum check and inference did not run for this reply. Max requires qualified compatible model evidence and is not offered by the current catalog.")
        }
        if (request == OrezAppInspection.MODEL_STATUS) return status
        return buildString {
            append("MangaLens ").append(snapshot.applicationVersion.take(80))
            append("; Android API ").append(snapshot.sdk).append('.')
            append("\nSupported Android ABIs: ")
            append(snapshot.supportedAbis.take(8).joinToString { it.take(64) }.ifBlank { "unavailable" }).append('.')
            append("\nOrez engine mode: ").append(snapshot.engineMode.take(40))
            append("; resource mode: ").append(snapshot.resourceMode.take(40)).append('.')
            append("\n\n").append(status)
        }
    }
}
