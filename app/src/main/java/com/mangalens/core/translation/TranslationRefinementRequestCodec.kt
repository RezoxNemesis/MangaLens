package com.mangalens.core.translation

import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezPinnedModelPolicy
import org.json.JSONObject

/** The journal captures every style flag and model pin; a missing pin stays unavailable. */
object TranslationRefinementRequestCodec {
    fun encode(request: TranslationRefinementRequest): JSONObject {
        validate(request)
        return JSONObject().put("enabled", request.enabled).put("style", JSONObject()
            .put("id", request.style.id).put("name", request.style.name).put("instruction", request.style.instruction)
            .put("preserveHonorifics", request.style.preserveHonorifics).put("preserveNames", request.style.preserveNames)
            .put("naturalDialogue", request.style.naturalDialogue))
            .apply { request.inputProfileRevision?.let { put("inputProfileRevision", it) } }
            .put("model", request.pinnedModel?.let { JSONObject().put("id", it.modelId).put("sha256", it.sha256).put("bytes", it.bytes) }
                ?: JSONObject.NULL)
    }

    fun decode(json: JSONObject): TranslationRefinementRequest {
        val style = json.getJSONObject("style")
        val model = if (!json.has("model") || json.isNull("model")) null else json.getJSONObject("model").let {
            OrezModelPin(it.getString("id"), it.getString("sha256"), it.getLong("bytes"))
        }
        return TranslationRefinementRequest(json.getBoolean("enabled"), TranslationStyleProfile(
            style.getString("id"), style.getString("name"), style.getString("instruction"),
            style.getBoolean("preserveHonorifics"), style.getBoolean("preserveNames"), style.getBoolean("naturalDialogue")), model,
            if (!json.has("inputProfileRevision") || json.isNull("inputProfileRevision")) null else json.getString("inputProfileRevision"))
            .also(::validate)
    }

    fun identity(request: TranslationRefinementRequest): String {
        validate(request)
        return (listOf(request.enabled.toString(), request.style.id, request.style.name, request.style.instruction,
            request.style.preserveHonorifics.toString(), request.style.preserveNames.toString(), request.style.naturalDialogue.toString(),
            request.pinnedModel?.modelId.orEmpty(), request.pinnedModel?.sha256.orEmpty(), request.pinnedModel?.bytes?.toString().orEmpty()) +
            listOfNotNull(request.inputProfileRevision?.let { "localization-input-profile:$it" }))
            .joinToString("|") { "${it.length}:$it" }
    }

    fun validate(request: TranslationRefinementRequest) {
        require(request.style.id.length in 1..64 && request.style.name.length in 1..128 && request.style.instruction.isNotBlank() &&
            request.style.instruction.length <= TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS) { "Captured translation style is invalid." }
        require(request.inputProfileRevision == null || request.inputProfileRevision.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Captured localization input revision is invalid." }
        require(request.pinnedModel == null || (request.enabled && OrezPinnedModelPolicy.valid(request.pinnedModel))) { "Captured refinement model is invalid." }
    }
}
