package com.mangalens.orez.agent

import com.mangalens.orez.OrezRoute
import java.util.UUID

enum class OrezTrustOrigin {
    USER,
    APP_STATE,
    WEB_CONTENT,
    IMPORTED_CONTENT
}

enum class OrezToolRisk {
    READ_ONLY,
    LOCAL_MUTATION,
    NETWORK_READ,
    NETWORK_MUTATION,
    ACCOUNT_MUTATION,
    DESTRUCTIVE
}

enum class OrezCapability {
    READER,
    VISION,
    TRANSLATION,
    MEDIA,
    DOWNLOADS,
    WEB,
    LIBRARY,
    SETTINGS,
    RESEARCH
}

enum class OrezTaskStatus {
    PLANNED,
    WAITING_APPROVAL,
    RUNNING,
    DISPATCHED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class OrezStepStatus {
    PENDING,
    RUNNING,
    DISPATCHED,
    COMPLETED,
    FAILED,
    BLOCKED
}

data class OrezAgentContext(
    val hasActiveChapter: Boolean = false,
    val hasLibrary: Boolean = false,
    val activeUrl: String? = null,
    val origin: OrezTrustOrigin = OrezTrustOrigin.USER,
    val explicitUserRequest: Boolean = true
)

data class OrezToolCall(
    val name: String,
    val capability: OrezCapability,
    val risk: OrezToolRisk,
    val summary: String,
    val arguments: Map<String, String> = emptyMap(),
    val route: OrezRoute? = null
)

data class OrezPlanStep(
    val index: Int,
    val call: OrezToolCall,
    val status: OrezStepStatus = OrezStepStatus.PENDING
)

data class OrezTaskPlan(
    val id: String = UUID.randomUUID().toString(),
    val objective: String,
    val steps: List<OrezPlanStep>,
    val status: OrezTaskStatus = OrezTaskStatus.PLANNED,
    val createdAt: Long = System.currentTimeMillis()
)

data class OrezAgentDecision(
    val plan: OrezTaskPlan? = null,
    val immediateRoute: OrezRoute? = null,
    val routeValue: String = "",
    val message: String = "",
    val continueToBrain: Boolean = false,
    val requiresApproval: Boolean = false
)
