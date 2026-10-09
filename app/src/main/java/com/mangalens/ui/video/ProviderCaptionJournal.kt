package com.mangalens.ui.video

import com.mangalens.download.*
import org.json.JSONArray
import org.json.JSONObject

/** These receipts stay inside the existing app-private, bounded, atomic subtitle journal. */
internal object ProviderCaptionJournal {
    fun inventory(value: ProviderCaptionInventory): JSONObject = JSONObject().put("sourcePageUrl", value.sourcePageUrl)
        .put("videoId", value.videoId).put("originalLanguage", value.originalLanguage).put("selectedAudioLanguage", value.selectedAudioLanguage)
        .put("expectedDurationMs", value.expectedDurationMs).put("tracks", JSONArray().apply { value.tracks.forEach { track ->
            put(JSONObject().put("url", track.url).put("language", track.language).put("kind", track.kind.name)
                .put("format", track.format.name).put("originalAutomatic", track.originalAutomatic))
        } })
    fun inventory(json: JSONObject): ProviderCaptionInventory {
        val rows = json.getJSONArray("tracks"); require(rows.length() in 1..128)
        return ProviderCaptionInventory(json.getString("sourcePageUrl"), json.optional("videoId"), json.optional("originalLanguage"),
            json.optional("selectedAudioLanguage"), if (json.isNull("expectedDurationMs")) null else json.getLong("expectedDurationMs"),
            (0 until rows.length()).map { index -> rows.getJSONObject(index).let { track -> ProviderCaptionTrack(track.getString("url"),
                track.getString("language"), ProviderCaptionKind.valueOf(track.getString("kind")), ProviderCaptionFormat.valueOf(track.getString("format")),
                track.getBoolean("originalAutomatic")) } }).also { it.validate() }
    }
    fun receipt(value: ProviderCaptionReceipt): JSONObject = JSONObject().put("taskId", value.taskId).put("generation", value.generation)
        .put("sourceFingerprint", value.sourceFingerprint).put("configFingerprint", value.configFingerprint).put("inventorySha256", value.inventorySha256)
        .put("trackUrlSha256", value.trackUrlSha256).put("language", value.language).put("kind", value.kind.name).put("format", value.format.name)
        .put("payloadSha256", value.payloadSha256).put("cuesSha256", value.cuesSha256).put("cueCount", value.cueCount).put("lastEndMs", value.lastEndMs)
    fun receipt(json: JSONObject): ProviderCaptionReceipt = ProviderCaptionReceipt(json.getString("taskId"), json.getString("generation"),
        json.getString("sourceFingerprint"), json.getString("configFingerprint"), json.getString("inventorySha256"), json.getString("trackUrlSha256"),
        json.getString("language"), ProviderCaptionKind.valueOf(json.getString("kind")), ProviderCaptionFormat.valueOf(json.getString("format")),
        json.getString("payloadSha256"), json.getString("cuesSha256"), json.getInt("cueCount"), json.getLong("lastEndMs"))
    private fun JSONObject.optional(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
