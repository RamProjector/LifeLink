package com.lifelink.app.domain

enum class UpdateType {
    REQUEST_STATUS,
    DONOR_RESPONSE,
    CONTACT_STATUS,
    ACCOUNT,
    SYSTEM
}

data class UpdateItem(
    val id: String,
    val type: UpdateType,
    val title: String,
    val body: String,
    val createdAtEpochMillis: Long,
    val requestId: String? = null,
    val actionKey: String? = null,
    val isRead: Boolean = false
)
