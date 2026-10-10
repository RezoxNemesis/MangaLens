package com.mangalens.orez.agent

import com.mangalens.orez.research.OrezResearchEvidenceCodec
import com.mangalens.orez.research.OrezResearchHost
import com.mangalens.orez.research.OrezResearchRequest
import kotlinx.coroutines.CancellationException

/** Only the trusted worker constructs this adapter from the exact captured task generation. */
internal class OrezResearchTools(private val plan: OrezTaskPlan, private val host: OrezResearchHost = OrezResearchHost(),
    private val isExecuting: suspend () -> Boolean) : OrezDurableTools {
    override suspend fun execute(step: OrezPlanStep, requestId: String): OrezToolResult = try {
        require(plan.authorization?.let { it.origin == OrezTrustOrigin.USER && it.explicitUserRequest } == true)
        require(step.call.name == "research_web" && step.references.isEmpty() && step.dependsOn.isEmpty())
        require(plan.steps.getOrNull(step.index)?.call == step.call)
        OrezToolRegistry().validate(step.call)
        require(requestId == OrezDurablePlanRules.requestId(plan.id, step.index))
        val request = OrezResearchRequest.captured(plan.objective, step.call.arguments)
        if (!isExecuting()) OrezToolResult.Pending("Research paused.", needsResume = true) else {
            val evidence = host.research(request, isExecuting)
            if (!isExecuting()) OrezToolResult.Pending("Research paused before publication.", needsResume = true)
            else if (evidence == null) OrezToolResult.Failed("No readable public source could be saved within the research limits. Try a more specific question.")
            else OrezToolResult.Completed(OrezResearchEvidenceCodec.outputs(requestId, evidence))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (paused: com.mangalens.core.compute.ResourcePausedException) {
          OrezToolResult.Pending("Waiting for device resources; research will retry automatically.")
      }
      catch (_: Exception) { OrezToolResult.Failed("Public research could not be completed. Check the connection and try again.") }
}
