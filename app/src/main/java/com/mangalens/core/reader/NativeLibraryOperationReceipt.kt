package com.mangalens.core.reader

import java.security.MessageDigest
import org.json.JSONObject

/** Stored alongside an actual changed value; this is evidence, never executable authority. */
data class NativeLibraryOperationReceipt(val requestId: String, val scopeIdentity: String, val operation: String,
    val valueSha256: String, val stateSha256: String, val completedAt: Long) {
    fun validate(): NativeLibraryOperationReceipt {
        require(requestId.length in 1..160 && requestId.none(Char::isISOControl))
        require(scopeIdentity.matches(Regex("[a-f0-9]{64}")) && valueSha256.matches(Regex("[a-f0-9]{64}")) && stateSha256.matches(Regex("[a-f0-9]{64}")))
        require(operation in setOf("BOOKMARK", "SET_TERM") && completedAt >= 0)
        return this
    }
    companion object {
        /** Stable semantic JSON identity excludes only this receipt; no unrelated edit is ignored. */
        fun stateDigest(body: JSONObject): String {
            fun canonical(value: Any?): String = when (value) {
                null, JSONObject.NULL -> "null"
                is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { key -> JSONObject.quote(key)+":"+canonical(value.get(key)) }
                is org.json.JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
                is String -> JSONObject.quote(value)
                is Boolean, is Int, is Long -> value.toString()
                else -> error("Library state contains an unsupported value type.")
            }
            val withoutReceipt = JSONObject(body.toString()).apply { remove("nativeLibraryOperation") }
            return valueDigest("native-library-state-v1",canonical(withoutReceipt))
        }
        fun valueDigest(vararg values: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value -> val bytes = value.toByteArray(Charsets.UTF_8); digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(':'.code.toByte()); digest.update(bytes) }
            return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT,it.toInt() and 255) }
        }
    }
}
internal object NativeLibraryOperationReceiptCodec {
    fun encode(value: NativeLibraryOperationReceipt): JSONObject = value.validate().let {
        JSONObject().put("version",1).put("requestId",it.requestId).put("scopeIdentity",it.scopeIdentity)
            .put("operation",it.operation).put("valueSha256",it.valueSha256).put("stateSha256",it.stateSha256).put("completedAt",it.completedAt)
    }
    fun decode(body: JSONObject): NativeLibraryOperationReceipt {
        require(body.keys().asSequence().toSet() == setOf("version","requestId","scopeIdentity","operation","valueSha256","stateSha256","completedAt"))
        require(body.get("version") == 1); val time = body.get("completedAt"); require(time is Int || time is Long)
        return NativeLibraryOperationReceipt(body.get("requestId") as String,body.get("scopeIdentity") as String,
            body.get("operation") as String,body.get("valueSha256") as String,body.get("stateSha256") as String,(time as Number).toLong()).validate()
    }
}
