package com.mangalens.core.translation

import org.json.JSONArray
import org.json.JSONObject

internal object SavedOcrDiagnosticsCodec {
    private fun box(value: SavedOcrBox) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
    private fun box(value: JSONArray): SavedOcrBox { require(value.length() == 4); return SavedOcrBox(value.getInt(0), value.getInt(1), value.getInt(2), value.getInt(3)) }
    fun encode(value: SavedPageOcrDiagnostics): JSONObject = JSONObject().put("version", 1).put("sha", value.sourceSha256)
        .put("w", value.width).put("h", value.height).put("reconstruction", value.reconstructionVersion).put("accepted", value.acceptedRegionCount)
        .put("panels", JSONArray().apply { value.proposedPanels.forEach { put(box(it)) } })
        .put("findings", JSONArray().apply { value.findings.forEach { row -> put(JSONObject().put("ordinal", row.ordinal)
            .put("bounds", box(row.bounds)).put("script", row.recognizerScript).put("confidence", row.mlKitDerivedConfidence)
            .put("lines", JSONArray().apply { row.lines.forEach { put(box(it)) } }).put("outcome", row.outcome.name)
            .put("native", row.nativeLetteringIndex).put("panel", row.proposedPanel).put("kind", row.geometryKind.name).put("kanaNeighbour", row.smallKanaNeighbourOrdinal).put("writable", row.writableBounds?.let(::box)).put("fitted", row.fittedSizeAtOne)) } })
    fun decode(json: JSONObject): SavedPageOcrDiagnostics {
        require(json.getInt("version") == 1)
        val rows = json.getJSONArray("findings"); val panels = json.getJSONArray("panels")
        require(rows.length() <= SavedPageOcrDiagnostics.MAX_FINDINGS && panels.length() <= 24)
        fun optionalInt(row: JSONObject, key: String) = if (row.has(key) && !row.isNull(key)) row.getInt(key) else null
        return SavedPageOcrDiagnostics(json.getString("sha"), json.getInt("w"), json.getInt("h"), json.getInt("reconstruction"), json.getInt("accepted"),
            (0 until rows.length()).map { index -> val row = rows.getJSONObject(index); val lines = row.getJSONArray("lines")
                require(lines.length() <= SavedPageOcrDiagnostics.MAX_LINES)
                SavedOcrFinding(row.getInt("ordinal"), box(row.getJSONArray("bounds")), row.getString("script"),
                    if (row.has("confidence") && !row.isNull("confidence")) row.getDouble("confidence").toFloat() else null,
                    (0 until lines.length()).map { box(lines.getJSONArray(it)) }, SavedOcrOutcome.valueOf(row.getString("outcome")),
                    optionalInt(row, "native"), optionalInt(row, "panel"), SavedOcrGeometryKind.valueOf(row.getString("kind")), optionalInt(row, "kanaNeighbour"), row.optJSONArray("writable")?.let(::box),
                    if (row.has("fitted") && !row.isNull("fitted")) row.getDouble("fitted").toFloat() else null) },
            (0 until panels.length()).map { box(panels.getJSONArray(it)) })
    }
    fun bytes(value: SavedPageOcrDiagnostics): Int = encode(value).toString().toByteArray(Charsets.UTF_8).size
    /** New detail cannot consume the journal space reserved for existing successful lettering. */
    fun bounded(value: SavedPageOcrDiagnostics, remainingBytes: Int): SavedPageOcrDiagnostics? {
        if (remainingBytes <= 0) return null
        var current = value
        while (bytes(current) > remainingBytes && current.findings.isNotEmpty()) current = current.copy(findings = current.findings.dropLast(1))
        if (bytes(current) > remainingBytes) current = current.copy(proposedPanels = emptyList(), findings = emptyList())
        return current.takeIf { bytes(it) <= remainingBytes }
    }
}
