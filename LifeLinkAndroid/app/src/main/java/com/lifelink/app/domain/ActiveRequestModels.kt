package com.lifelink.app.domain

enum class ActiveRequestStatus(val label: String) {
    AWAITING_RESPONSES("Waiting for donor responses"),
    MATCHING("Finding eligible donors"),
    MANUAL_BROADCAST("Manual broadcast pending"),
    FULFILLED("Request fulfilled"),
    EXPIRED("Request expired"),
    CANCELLED("Request cancelled")
}

data class ActiveRequestSnapshot(
    val requestId: String,
    val status: ActiveRequestStatus,
    val notificationsCreated: Int = 0,
    val matchesResponded: Int = 0,
    val reason: String? = null,
    val lastUpdatedEpochMillis: Long = System.currentTimeMillis()
) {
    val isTerminal: Boolean
        get() = status == ActiveRequestStatus.FULFILLED || status == ActiveRequestStatus.EXPIRED || status == ActiveRequestStatus.CANCELLED
}
