package com.lifelink.app.data.repository

import com.lifelink.app.data.remote.ContactShareRequest
import com.lifelink.app.data.remote.DonorMapVisibilityRequest
import com.lifelink.app.data.remote.LifeLinkApi
import com.lifelink.app.data.remote.MessageRequest
import com.lifelink.app.domain.ChatMessage
import com.lifelink.app.domain.ContactShare
import com.lifelink.app.domain.Conversation
import com.lifelink.app.domain.DonorMap
import com.lifelink.app.domain.DonorMapArea
import com.lifelink.app.domain.DonorMapVisibility
import com.lifelink.app.domain.MatchedDonorLocation
import com.lifelink.app.domain.PrivacyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remote-only repository for the privacy features. Nothing here caches exact
 * coordinates: every read re-asks the server, which re-checks the live share.
 */
class PrivacyRepositoryImpl(
    private val api: LifeLinkApi,
    private val donorIdProvider: () -> String? = { null }
) : PrivacyRepository {

    private fun donorId(): String =
        donorIdProvider()?.takeIf { it.isNotBlank() } ?: error("Sign in before using donor mode.")

    override suspend fun donorMap(): Result<DonorMap> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.donorMap()
            check(response.isSuccessful) { "Donor map could not be loaded (${response.code()})." }
            val body = checkNotNull(response.body()) { "The server returned an empty donor map." }
            DonorMap(
                generatedAt = body.generatedAt,
                approximateOnly = body.approximateOnly,
                areas = body.entries.map {
                    DonorMapArea(
                        areaLabel = it.areaLabel,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        radiusMeters = it.radiusMeters,
                        bloodType = it.bloodType,
                        availability = it.availability,
                        freshnessAt = it.freshnessAt,
                        freshnessAgeMinutes = it.freshnessAgeMinutes,
                        isStale = it.isStale
                    )
                }
            )
        }
    }

    override suspend fun setMapVisibility(
        mapVisible: Boolean,
        exactLocationSharingEnabled: Boolean
    ): Result<DonorMapVisibility> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.setDonorMapVisibility(
                donorId(),
                DonorMapVisibilityRequest(mapVisible, exactLocationSharingEnabled)
            )
            check(response.isSuccessful) { "Map visibility could not be updated (${response.code()})." }
            val body = checkNotNull(response.body()) { "The server returned an empty visibility response." }
            DonorMapVisibility(
                mapVisible = body.mapVisible,
                exactLocationSharingEnabled = body.exactLocationSharingEnabled,
                updatedAt = body.mapVisibilityUpdatedAt,
                freshnessAt = body.freshnessAt
            )
        }
    }

    override suspend fun activateLocationShare(requestId: String, donorId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.activateLocationShare(requestId, donorId)
                check(response.isSuccessful) { "Exact location sharing could not be activated (${response.code()})." }
            }
        }

    override suspend fun revokeLocationShare(requestId: String, donorId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.revokeLocationShare(requestId, donorId)
                check(response.isSuccessful) { "Exact location sharing could not be revoked (${response.code()})." }
            }
        }

    override suspend fun matchedDonorLocation(requestId: String, donorId: String): Result<MatchedDonorLocation> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.matchedDonorLocation(requestId, donorId)
                check(response.isSuccessful) { "Donor location could not be loaded (${response.code()})." }
                val body = checkNotNull(response.body()) { "The server returned an empty location response." }
                MatchedDonorLocation(
                    requestId = body.requestId,
                    donorId = body.donorId,
                    shared = body.shared,
                    latitude = body.latitude,
                    longitude = body.longitude,
                    precisionMeters = body.precisionMeters,
                    freshnessAt = body.freshnessAt,
                    expiresAt = body.expiresAt,
                    reason = body.reason
                )
            }
        }

    override suspend fun openConversation(requestId: String, donorId: String): Result<Conversation> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.openConversation(requestId, donorId)
                check(response.isSuccessful) { "Conversation could not be opened (${response.code()})." }
                val body = checkNotNull(response.body()) { "The server returned an empty conversation." }
                Conversation(
                    conversationId = body.conversationId,
                    requestId = body.requestId,
                    donorId = body.donorId,
                    requesterId = body.requesterId,
                    lastMessageAt = body.lastMessageAt,
                    createdAt = body.createdAt
                )
            }
        }

    override suspend fun messages(conversationId: String): Result<List<ChatMessage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.listMessages(conversationId)
                check(response.isSuccessful) { "Messages could not be loaded (${response.code()})." }
                checkNotNull(response.body()).map {
                    ChatMessage(it.messageId, it.conversationId, it.senderId, it.body, it.createdAt)
                }
            }
        }

    override suspend fun sendMessage(conversationId: String, body: String): Result<ChatMessage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.sendMessage(conversationId, MessageRequest(body.trim()))
                check(response.isSuccessful) { "Message could not be sent (${response.code()})." }
                val sent = checkNotNull(response.body()) { "The server returned an empty message." }
                ChatMessage(sent.messageId, sent.conversationId, sent.senderId, sent.body, sent.createdAt)
            }
        }

    override suspend fun contactShares(conversationId: String): Result<List<ContactShare>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.listContactShares(conversationId)
                check(response.isSuccessful) { "Contact shares could not be loaded (${response.code()})." }
                checkNotNull(response.body()).map {
                    ContactShare(it.shareId, it.conversationId, it.sharedBy, it.field, it.value, it.createdAt)
                }
            }
        }

    override suspend fun shareContact(conversationId: String, field: String, value: String): Result<ContactShare> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.shareContactDetails(conversationId, ContactShareRequest(field, value.trim()))
                check(response.isSuccessful) { "Contact details could not be shared (${response.code()})." }
                val share = checkNotNull(response.body()) { "The server returned an empty contact share." }
                ContactShare(share.shareId, share.conversationId, share.sharedBy, share.field, share.value, share.createdAt)
            }
        }
}
