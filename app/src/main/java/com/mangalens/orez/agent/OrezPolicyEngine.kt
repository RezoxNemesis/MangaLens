package com.mangalens.orez.agent

data class OrezPolicyVerdict(
    val allowed: Boolean,
    val requiresApproval: Boolean = false,
    val reason: String = ""
)

class OrezPolicyEngine {
    fun evaluate(call: OrezToolCall, context: OrezAgentContext): OrezPolicyVerdict {
        if (context.origin == OrezTrustOrigin.WEB_CONTENT || context.origin == OrezTrustOrigin.IMPORTED_CONTENT) {
            if (!context.explicitUserRequest) {
                return OrezPolicyVerdict(
                    allowed = false,
                    reason = "Untrusted page or imported content cannot initiate MangaLens actions."
                )
            }
            if (call.risk !in setOf(OrezToolRisk.READ_ONLY, OrezToolRisk.NETWORK_READ)) {
                return OrezPolicyVerdict(
                    allowed = false,
                    reason = "Untrusted content cannot authorize state-changing actions."
                )
            }
        }

        return when (call.risk) {
            OrezToolRisk.READ_ONLY,
            OrezToolRisk.NETWORK_READ,
            OrezToolRisk.LOCAL_MUTATION -> OrezPolicyVerdict(allowed = true)

            OrezToolRisk.NETWORK_MUTATION,
            OrezToolRisk.ACCOUNT_MUTATION,
            OrezToolRisk.DESTRUCTIVE -> OrezPolicyVerdict(
                allowed = true,
                requiresApproval = true,
                reason = "This action changes external or destructive state and needs explicit approval."
            )
        }
    }
}
