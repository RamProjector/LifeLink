package com.lifelink.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_request_drafts")
data class EmergencyRequestDraftEntity(
    @PrimaryKey val id: String,
    val bloodType: String?,
    val units: Int,
    val typeUnknown: Boolean,
    val urgency: String,
    val responseDeadline: String,
    val note: String,
    val facilityId: String?,
    val facilityName: String?,
    val facilityArea: String?,
    val facilityVerified: Boolean,
    val contactMethod: String,
    val genuineRequestConfirmed: Boolean,
    val sharingConsentConfirmed: Boolean,
    val aiMatchingEnabled: Boolean,
    val updatedAtEpochMillis: Long
)
