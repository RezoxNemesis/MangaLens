package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import org.json.JSONArray
import org.json.JSONObject

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
    suspend fun checkpoint(
        plan: OrezTaskPlan,
        status: OrezTaskStatus = plan.status,
        error: String? = null
    ) {
        dao.upsert(
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
            )
        }
        root.put("steps", steps)
        return root.toString()
    }

    companion object {
        private const val DEFAULT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
