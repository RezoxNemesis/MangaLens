package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import org.json.JSONArray
import org.json.JSONObject
import com.mangalens.orez.OrezRoute

/**
 * Durable journal for autonomous Orez work.
 *
 * Plans are checkpointed before execution so process death or navigation does not
 * erase the user's objective. The JSON is intentionally schema-versioned so future
 * Orez releases can migrate richer task graphs without coupling Room columns to
 * every tool argument.
 */
class OrezTaskStore(
    private val dao: OrezTaskDao
) {
    suspend fun load(id: String): OrezTaskPlan? = dao.get(id)?.let { entity ->
        runCatching { decode(entity.planJson) }.getOrNull()
    }

    internal fun decode(encoded: String): OrezTaskPlan {
        require(encoded.length <= 128 * 1024) { "Orez journal exceeds the safe limit" }
        val root = JSONObject(encoded)
        require(root.getInt("schema") == 1) { "Unsupported Orez task schema" }
        val steps = root.getJSONArray("steps")
        require(steps.length() in 1..32) { "Invalid task step count" }
        return OrezTaskPlan(
            id = root.getString("id"),
            objective = root.getString("objective"),
            status = OrezTaskStatus.valueOf(root.getString("status")),
            createdAt = root.getLong("createdAt"),
            steps = (0 until steps.length()).map { i ->
                val step = steps.getJSONObject(i)
                val args = step.getJSONObject("arguments")
                OrezPlanStep(
                    index = step.getInt("index"),
                    outputs = step.optJSONObject("outputs")?.let { values ->
                        values.keys().asSequence().associateWith { values.getString(it) }
                    }.orEmpty(),
                    status = OrezStepStatus.valueOf(step.getString("status")),
                    call = OrezToolCall(
                        name = step.getString("tool"),
                        capability = OrezCapability.valueOf(step.getString("capability")),
                        risk = OrezToolRisk.valueOf(step.getString("risk")),
                        summary = step.getString("summary"),
                        route = if (step.isNull("route")) null else OrezRoute.valueOf(step.getString("route")),
                        arguments = args.keys().asSequence().associateWith { args.getString(it) }
                    )
                )
            }
        )
    }
    suspend fun checkpoint(
        plan: OrezTaskPlan,
        status: OrezTaskStatus = plan.status,
        error: String? = null
    ): Boolean {
        return dao.checkpointIfNotCancelled(
            OrezTaskEntity(
                id = plan.id,
                objective = plan.objective,
                status = status.name,
                planJson = encode(plan.copy(status = status)),
                createdAt = plan.createdAt,
                updatedAt = System.currentTimeMillis(),
                lastError = error
            )
        )
    }

    suspend fun pruneFinished(retentionMs: Long = DEFAULT_RETENTION_MS) {
        dao.pruneFinished(System.currentTimeMillis() - retentionMs)
    }

    private fun encode(plan: OrezTaskPlan): String {
        val root = JSONObject()
            .put("schema", 1)
            .put("id", plan.id)
            .put("objective", plan.objective)
            .put("status", plan.status.name)
            .put("createdAt", plan.createdAt)

        val steps = JSONArray()
        plan.steps.forEach { step ->
            val args = JSONObject()
            step.call.arguments.forEach { (key, value) -> args.put(key, value) }

            steps.put(
                JSONObject()
                    .put("index", step.index)
                    .put("status", step.status.name)
                    .put("tool", step.call.name)
                    .put("capability", step.call.capability.name)
                    .put("risk", step.call.risk.name)
                    .put("summary", step.call.summary)
                    .put("route", step.call.route?.name ?: JSONObject.NULL)
                    .put("arguments", args)
                    .put("outputs", JSONObject(step.outputs))
            )
        }
        root.put("steps", steps)
        return root.toString()
    }

    companion object {
        private const val DEFAULT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }
}

