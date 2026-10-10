package com.mangalens.orez.agent

import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/** A bounded native manifest-byte revision, not a claim about original image contents. */
data class OrezLibraryManifestRevision(val chapterId: String, val sha256: String, val bytes: Long, val sourceTopologySha256: String) {
    fun validate() { require(chapterId.matches(Regex("[a-f0-9]{32}")) && sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 1..2_000_000L && sourceTopologySha256.matches(Regex("[a-f0-9]{64}"))) }
}
data class OrezLibrarySeriesRevision(val seriesId: String, val associationRevision: Long, val chapterJournalSha256: String, val profileSha256: String) {
    fun validate() { require(seriesId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && associationRevision >= 0 &&
        chapterJournalSha256.matches(Regex("[a-f0-9]{64}")) && profileSha256.matches(Regex("[a-f0-9]{64}"))) }
}
class OrezLibraryScope private constructor(val request: OrezLibraryRequest, val selectedChapterId: String?,
    inventory: List<OrezLibraryManifestRevision>, val incomplete: Boolean, val series: OrezLibrarySeriesRevision?) {
    val inventory: List<OrezLibraryManifestRevision> = Collections.unmodifiableList(inventory.toList())
    val identity: String = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            fun field(value: String) { val utf8 = value.toByteArray(Charsets.UTF_8); output.writeInt(utf8.size); output.write(utf8) }
            field("orez-native-library-scope-v1"); field(request.operation.name); field(request.query)
            field(request.bookmarked?.toString().orEmpty()); field(request.source); field(request.preferred); field(request.target)
            output.writeInt(request.limit); field(selectedChapterId.orEmpty()); output.writeBoolean(incomplete); output.writeInt(this.inventory.size)
            this.inventory.forEach { field(it.chapterId); field(it.sha256); output.writeLong(it.bytes); field(it.sourceTopologySha256) }
            output.writeBoolean(series != null)
            series?.let { field(it.seriesId); output.writeLong(it.associationRevision); field(it.chapterJournalSha256); field(it.profileSha256) }
        }
    }.toByteArray().let(::digest)
    fun validate(): OrezLibraryScope {
        request.validate(); require(inventory.size <= MAX_MANIFESTS && inventory.map { it.chapterId }.distinct().size == inventory.size)
        inventory.forEach { it.validate() }; require(inventory.sumOf { it.bytes } <= MAX_BYTES)
        if (request.operation.selected) require(selectedChapterId != null && inventory.size == 1 && inventory.single().chapterId == selectedChapterId)
        else require(selectedChapterId == null && series == null)
        if (request.operation in setOf(OrezLibraryOperation.READ_SERIES, OrezLibraryOperation.SET_TERM)) requireNotNull(series).validate()
        else require(series == null)
        return this
    }
    override fun equals(other: Any?): Boolean = other is OrezLibraryScope && identity == other.identity
    override fun hashCode() = identity.hashCode()
    companion object {
        const val MAX_MANIFESTS = 128
        const val MAX_DIRECTORY_ENTRIES = 512
        const val MAX_BYTES = 16L * 1024 * 1024
        fun capture(request: OrezLibraryRequest, selectedChapterId: String?, inventory: List<OrezLibraryManifestRevision>, incomplete: Boolean,
            series: OrezLibrarySeriesRevision? = null): OrezLibraryScope {
            request.validate(); require(inventory.size <= MAX_MANIFESTS)
            val captured = ArrayList<OrezLibraryManifestRevision>(); for (entry in inventory) { require(captured.size < MAX_MANIFESTS); entry.validate(); captured += entry }
            require(captured.sumOf { it.bytes } <= MAX_BYTES); series?.validate()
            return OrezLibraryScope(request, selectedChapterId, captured, incomplete, series).validate()
        }
        internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
}
internal object OrezLibraryScopeCodec {
    private fun JSONObject.strictString(key: String): String = get(key) as String
    fun encodeBody(scope: OrezLibraryScope): JSONObject = JSONObject().put("version",1)
        .put("operation",scope.request.operation.name).put("query",scope.request.query)
        .put("bookmarked",scope.request.bookmarked ?: JSONObject.NULL).put("source",scope.request.source)
        .put("preferred",scope.request.preferred).put("target",scope.request.target).put("limit",scope.request.limit)
        .put("selectedChapterId",scope.selectedChapterId ?: JSONObject.NULL).put("incomplete",scope.incomplete)
        .put("inventory",JSONArray(scope.inventory.map { JSONObject().put("chapterId",it.chapterId).put("sha256",it.sha256).put("bytes",it.bytes).put("sourceTopologySha256",it.sourceTopologySha256) }))
        .put("series",scope.series?.let { JSONObject().put("id",it.seriesId).put("associationRevision",it.associationRevision)
            .put("chapterJournalSha256",it.chapterJournalSha256).put("profileSha256",it.profileSha256) } ?: JSONObject.NULL)
    fun encode(scope: OrezLibraryScope): JSONObject = encodeBody(scope.validate()).put("identity",scope.identity)
    fun decode(body: JSONObject): OrezLibraryScope {
        require(body.keys().asSequence().toSet() == setOf("version","operation","query","bookmarked","source","preferred","target","limit","selectedChapterId","incomplete","inventory","series","identity"))
        require(body.get("version") == 1)
        val limit = body.get("limit"); require(limit is Int || limit is Long); require((limit as Number).toLong() in 1..24)
        val request = OrezLibraryRequest(OrezLibraryOperation.valueOf(body.strictString("operation")), body.strictString("query"),
            if (body.isNull("bookmarked")) null else body.get("bookmarked") as Boolean, body.strictString("source"),body.strictString("preferred"),body.strictString("target"),limit.toInt())
        val rows = body.getJSONArray("inventory"); require(rows.length() <= OrezLibraryScope.MAX_MANIFESTS)
        val inventory = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            require(row.keys().asSequence().toSet() == setOf("chapterId","sha256","bytes","sourceTopologySha256")); val bytes = row.get("bytes"); require(bytes is Int || bytes is Long)
            OrezLibraryManifestRevision(row.strictString("chapterId"),row.strictString("sha256"),(bytes as Number).toLong(),row.strictString("sourceTopologySha256"))
        } }
        val seriesValue = body.get("series"); require(seriesValue == JSONObject.NULL || seriesValue is JSONObject)
        val series = (seriesValue as? JSONObject)?.let { row ->
            require(row.keys().asSequence().toSet() == setOf("id","associationRevision","chapterJournalSha256","profileSha256"))
            val revision = row.get("associationRevision"); require(revision is Int || revision is Long)
            OrezLibrarySeriesRevision(row.strictString("id"),(revision as Number).toLong(),row.strictString("chapterJournalSha256"),row.strictString("profileSha256"))
        }
        return OrezLibraryScope.capture(request,if (body.isNull("selectedChapterId")) null else body.strictString("selectedChapterId"),inventory,body.get("incomplete") as Boolean,series)
            .also { require(it.identity == body.strictString("identity")) { "Captured Library scope identity changed." } }
    }
}
