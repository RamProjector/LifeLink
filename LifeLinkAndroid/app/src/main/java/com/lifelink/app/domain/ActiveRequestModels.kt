package com.lifelink.app.domain

enum class ActiveRequestStatus(val label: String) {
    DRAFT("Draft"),
    MATCHING("Finding eligible donors"),
    AWAITING_RESPONSES("Waiting for donor responses"),
    MANUAL_BROADCAST("Manual broadcast pending"),
    PARTIALLY_FULFILLED("Partially fulfilled"),
    FULFILLED("Request fulfilled"),
    EXPIRED("Request expired"),
    CANCELLED("Request cancelled"),
    UNKNOWN("Status unavailable"),
    ;

    val isTerminal: Boolean
        get() = this == FULFILLED || this == EXPIRED || this == CANCELLED

    val supportsContactReview: Boolean
        get() = this != DRAFT && this != UNKNOWN

    companion object {
        fun fromApi(value: String?): ActiveRequestStatus {
            val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class ActiveRequestSnapshot(
    val requestId: String,
    val status: ActiveRequestStatus,
    val notificationsCreated: Int = 0,
    val matchesResponded: Int = 0,
    val reason: String? = null,
    val lastUpdatedEpochMillis: Long = System.currentTimeMillis(),
) {
    val isTerminal: Boolean
        get() = status.isTerminal

    val supportsLifecycleActions: Boolean
        get() = status.supportsContactReview && !isTerminal
}
