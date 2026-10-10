package com.mangalens.orez

import org.json.JSONObject

/** A generated editable search action is separate from retrieved evidence and Play cards. */
internal object OrezVideoSearchActionCodec {
    const val MARKER = "\n\nVideo search action:\n"
    fun encode(query: String?): String = if (query != null && OrezPublicVideoDiscovery.validQuery(query))
        MARKER + JSONObject().put("schema", 1).put("query", query).toString() else ""
    fun fromMessage(message: String): String? = runCatching {
        val at = message.indexOf(MARKER)
        require(at >= 0 && message.indexOf(MARKER, at + MARKER.length) == -1)
        val raw = message.substring(at + MARKER.length); require(raw.length <= 2048)
        val row = JSONObject(raw)
        require(row.keys().asSequence().toSet() == setOf("schema", "query") && row.getInt("schema") == 1)
        (row.get("query") as? String)?.takeIf(OrezPublicVideoDiscovery::validQuery)
    }.getOrNull()
}
