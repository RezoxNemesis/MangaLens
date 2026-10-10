package com.mangalens.orez.agent

import org.json.JSONArray
import org.json.JSONObject

/** A metadata receipt never becomes a source, a route, another tool argument or a model grant. */
internal object OrezLibraryRules {
    fun validate(plan: OrezTaskPlan): OrezLibraryScope {
        val authorization = requireNotNull(plan.authorization)
        require(authorization.origin == OrezTrustOrigin.USER && authorization.explicitUserRequest)
        val scope = requireNotNull(authorization.libraryScope) { "Library metadata was not captured by this explicit user request." }.validate()
        val step = plan.steps.single(); require(step.index == 0 && step.references.isEmpty() && step.dependsOn.isEmpty())
        val request = OrezLibraryRequest.captured(plan.objective,step.call.name,step.call.arguments,scope.selectedChapterId)
        require(request == scope.request && authorization.urls.isEmpty() && authorization.translation == null &&
            authorization.chapterAcquisition == null && authorization.selectedMedia == null && authorization.subtitle == null)
        if (request.operation.selected) require(authorization.chapterIds == setOf(requireNotNull(scope.selectedChapterId)))
        else require(authorization.chapterIds.isEmpty())
        return scope
    }
    fun receipt(plan: OrezTaskPlan, step: OrezPlanStep, output: Map<String,String>, completed: Boolean) {
        require(completed); val scope = validate(plan)
        require(output.keys == setOf("requestId","scopeIdentity","operation","chapterId","seriesId","associationRevision","metadata","incomplete","persisted","nativeReceipt"))
        require(output["requestId"] == OrezDurablePlanRules.requestId(plan.id,step.index) && output["scopeIdentity"] == scope.identity &&
            output["operation"] == scope.request.operation.name && output["chapterId"] == scope.selectedChapterId.orEmpty() &&
            output["seriesId"] == scope.series?.seriesId.orEmpty() && output["associationRevision"] == scope.series?.associationRevision?.toString().orEmpty() &&
            output["persisted"] == scope.request.operation.mutation.toString() && output["incomplete"] in setOf("true","false"))
        val body = JSONObject(output.getValue("metadata")); require(body.keys().asSequence().toSet() == setOf("trust","chapters","seriesId","seriesTitle","terms","styleId","styleTarget","styleInstructions"))
        require(body.get("trust") == "APP_SAVED_METADATA")
        require(if (scope.series == null) body.isNull("seriesId") && body.isNull("seriesTitle") else body.get("seriesId") == scope.series.seriesId && (body.get("seriesTitle") as String).length <= 256)
        val rows = body.getJSONArray("chapters"); require(rows.length() <= scope.request.limit)
        val ids = mutableSetOf<String>()
        for (n in 0 until rows.length()) {
            val row = rows.getJSONObject(n); require(row.keys().asSequence().toSet() == setOf("chapterId","title","series","pageCount","position","bookmarked","readingStatus","addedAt","lastReadAt"))
            val id = row.get("chapterId") as String; require(ids.add(id) && id in scope.inventory.map { it.chapterId })
            require((row.get("title") as String).length <= 256 && (row.get("series") as String).length <= 128 && row.get("bookmarked") is Boolean)
            fun integer(key: String): Long { val value = row.get(key); require(value is Int || value is Long); return (value as Number).toLong() }
            require(integer("pageCount") in 1..100_000 && integer("position") in 0 until integer("pageCount") && integer("addedAt") >= 0 && integer("lastReadAt") >= 0)
            require(row.get("readingStatus") in com.mangalens.core.reader.ReadingStatus.entries.map { it.name })
            if (scope.request.operation == OrezLibraryOperation.BOOKMARK) require(row.get("bookmarked") == scope.request.bookmarked)
        }
        val terms = body.getJSONArray("terms"); require(terms.length() <= scope.request.limit && (scope.series != null || terms.length() == 0))
        for (n in 0 until terms.length()) {
            val term = terms.getJSONObject(n); require(term.keys().asSequence().toSet() == setOf("source","preferred","targetLanguage","origin"))
            require((term.get("source") as String).let { it.isNotBlank() && it.length <= 256 } && (term.get("preferred") as String).let { it.isNotBlank() && it.length <= 256 })
            require((term.get("targetLanguage") as String).matches(Regex("[A-Za-z][A-Za-z0-9-]{0,31}")) && term.get("origin") in setOf("USER_LITERAL","SAVED_SOURCE_METADATA"))
        }
        if (scope.request.operation.mutation) {
            val receipt = com.mangalens.core.reader.NativeLibraryOperationReceiptCodec.decode(JSONObject(output.getValue("nativeReceipt")))
            val expected = when (scope.request.operation) {
                OrezLibraryOperation.BOOKMARK -> com.mangalens.core.reader.NativeLibraryOperationReceipt.valueDigest("BOOKMARK",requireNotNull(scope.selectedChapterId),
                    scope.inventory.single().sourceTopologySha256,scope.request.bookmarked.toString()).also { require(rows.length() == 1) }
                OrezLibraryOperation.SET_TERM -> com.mangalens.core.reader.NativeLibraryOperationReceipt.valueDigest("SET_TERM",requireNotNull(scope.series).seriesId,
                    scope.request.source,scope.request.preferred,scope.request.target).also {
                    require(terms.length() == 1); val term = terms.getJSONObject(0)
                    require(term.get("source") == scope.request.source && term.get("preferred") == scope.request.preferred &&
                        term.get("targetLanguage") == scope.request.target && term.get("origin") == "USER_LITERAL")
                }
                else -> error("Unknown metadata mutation")
            }
            require(receipt.requestId == output["requestId"] && receipt.scopeIdentity == scope.identity && receipt.operation == scope.request.operation.name && receipt.valueSha256 == expected)
        } else require(output["nativeReceipt"].isNullOrEmpty())
        if (scope.series == null || body.isNull("styleId")) require(body.isNull("styleId") && body.isNull("styleTarget") && body.isNull("styleInstructions"))
        else require(!body.isNull("styleTarget") && !body.isNull("styleInstructions"))
        if (!body.isNull("styleInstructions")) require(body.get("styleInstructions") is String && (body.get("styleInstructions") as String).length <= 2_048)
        listOf("styleId","styleTarget").forEach { key -> if (!body.isNull(key)) require(body.get(key) is String && (body.get(key) as String).length <= 64) }
    }
    fun completion(output: Map<String,String>): String {
        val operation = OrezLibraryOperation.valueOf(output.getValue("operation")); val body = JSONObject(output.getValue("metadata"))
        val chapters = body.getJSONArray("chapters"); val terms = body.getJSONArray("terms")
        val coverage = if (output["incomplete"] == "true") " Results are bounded; more saved metadata may exist." else ""
        return when (operation) {
            OrezLibraryOperation.BOOKMARK -> "Saved and read back the selected chapter's ${if (chapters.getJSONObject(0).getBoolean("bookmarked")) "bookmark" else "unbookmarked status"}."
            OrezLibraryOperation.SET_TERM -> terms.getJSONObject(0).let { "Saved and read back your term “${it.getString("source")}” → “${it.getString("preferred")}” (${it.getString("targetLanguage")}) in the existing series glossary. No source or OCR provenance was added." }
            OrezLibraryOperation.READ_SERIES -> "Saved series metadata: ${body.getString("seriesTitle")}. ${terms.length()} glossary entries.$coverage" +
                (0 until terms.length()).joinToString("",prefix="") { n -> terms.getJSONObject(n).let { "\n${it.getString("source")} → ${it.getString("preferred")} (${it.getString("targetLanguage")}; ${it.getString("origin")})" } } +
                (if (body.isNull("styleId")) "" else "\nStyle: ${body.getString("styleId")} (${body.optString("styleTarget")}).") + (if (body.isNull("styleInstructions") || body.getString("styleInstructions").isBlank()) "" else "\nCustom preference: ${body.getString("styleInstructions")}")
            else -> "${chapters.length()} saved chapter metadata results.$coverage" + (0 until chapters.length()).joinToString("") { n -> chapters.getJSONObject(n).let {
                "\n${it.getString("title")} • ${it.getInt("pageCount")} pages • ${it.getString("readingStatus")}${if (it.getBoolean("bookmarked")) " • bookmarked" else ""}" } }
        }
    }
}
