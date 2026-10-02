package com.lifelink.app.domain

/**
 * Domain models for the confirmed LifeLink privacy behavior.
 *
 * The donor map only ever carries an approximate area and a freshness
 * timestamp. Exact coordinates are represented separately by [MatchedDonorLocation]
 * and are only populated when the server confirms a live, matched-requester share.
 */

data class DonorMapArea(
    val areaLabel: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val bloodType: String,
    val availability: String,
    val freshnessAt: String,
    val freshnessAgeMinutes: Int,
    val isStale: Boolean,
)

data class DonorMap(val generatedAt: String, val approximateOnly: Boolean, val areas: List<DonorMapArea>)

data class DonorMapVisibility(
    val mapVisible: Boolean,
    val exactLocationSharingEnabled: Boolean,
    val updatedAt: String? = null,
    val freshnessAt: String? = null,
)

data class MatchedDonorLocation(
    val requestId: String,
    val donorId: String,
    val shared: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val precisionMeters: Int? = null,
    val freshnessAt: String? = null,
    val expiresAt: String? = null,
    val reason: String? = null,
)

data class Conversation(
    val conversationId: String,
    val requestId: String,
    val donorId: String,
    val requesterId: String,
    val lastMessageAt: String? = null,
    val createdAt: String,
)

data class ChatMessage(val messageId: String, val conversationId: String, val senderId: String, val body: String, val createdAt: String)

data class ContactShare(
    val shareId: String,
    val conversationId: String,
    val sharedBy: String,
    val field: String,
    val value: String,
    val createdAt: String,
)

interface PrivacyRepository {
    suspend fun donorMap(): Result<DonorMap>

    suspend fun setMapVisibility(mapVisible: Boolean, exactLocationSharingEnabled: Boolean): Result<DonorMapVisibility>

    suspend fun activateLocationShare(requestId: String, donorId: String): Result<Unit>

    suspend fun revokeLocationShare(requestId: String, donorId: String): Result<Unit>

    suspend fun matchedDonorLocation(requestId: String, donorId: String): Result<MatchedDonorLocation>

    suspend fun openConversation(requestId: String, donorId: String): Result<Conversation>

    suspend fun conversations(): Result<List<Conversation>>

    suspend fun messages(conversationId: String): Result<List<ChatMessage>>

    suspend fun sendMessage(conversationId: String, body: String): Result<ChatMessage>

    suspend fun contactShares(conversationId: String): Result<List<ContactShare>>

    suspend fun shareContact(conversationId: String, field: String, value: String): Result<ContactShare>

    suspend fun reportParticipant(conversationId: String, reason: String): Result<Unit>

    suspend fun blockParticipant(conversationId: String): Result<Unit>
}
