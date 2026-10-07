package com.mangalens.orez.agent

import org.json.JSONObject

/** Strict boundary between model suggestions and executable app capabilities. */
class OrezModelPlanDecoder {
    fun decode(response: String, objective: String, context: OrezAgentContext): OrezTaskPlan? = runCatching {
        require(context.origin == OrezTrustOrigin.USER && context.explicitUserRequest)
        require(response.length <= 8192)
        val root = JSONObject(response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        require(root.keys().asSequence().toSet() == setOf("tool", "arguments"))
        val args = root.getJSONObject("arguments")
        require(args.length() <= 2)
        val arguments = args.keys().asSequence().associateWith { key ->
            require(args.get(key) is String)
            args.getString(key)
        }
        val call = OrezToolRegistry().call(root.getString("tool"), arguments)
        val action = ACTION.find(objective.trim())?.groupValues?.get(1)?.lowercase() ?: error("No direct action request")
        val allowed = when(action) {
            "download", "save" -> call.name == "enqueue_download"
            "translate", "अनुवाद" -> call.capability == OrezCapability.TRANSLATION
            "play", "watch", "chalao", "चलाओ" -> call.name == "open_video_url"
            "read" -> call.name == "open_reader_url"
            else -> call.name.startsWith("open_")
        }
        require(allowed) { "Model tool does not match the requested action" }
        require(call.name != "translate_active_chapter" || context.hasActiveChapter)
        require(OrezPolicyEngine().evaluate(call, context).let { it.allowed && !it.requiresApproval })
        OrezTaskPlan(objective = objective, steps = listOf(OrezPlanStep(0, call)))
    }.getOrNull()

    companion object {
        // Questions/instructions quoted inside source content are not action requests.
        private val ACTION = Regex(
            "^(?:(?:please|can you|could you)\\s+)?(open|show|play|watch|read|download|save|translate|khol|dikhao|chalao|अनुवाद|खोलो|दिखाओ|चलाओ)\\b",
            RegexOption.IGNORE_CASE)
        fun isActionRequest(input: String): Boolean = ACTION.containsMatchIn(input.trim())
    }
}
