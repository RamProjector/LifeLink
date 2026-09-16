package com.lifelink.app.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.lifelink.app.data.local.ActiveRequestDao
import com.lifelink.app.data.local.ActiveRequestEntity
import com.lifelink.app.data.local.EmergencyRequestDraftDao
import com.lifelink.app.data.local.EmergencyRequestDraftEntity
import com.lifelink.app.data.local.PendingSubmissionDao
import com.lifelink.app.data.local.PendingSubmissionEntity
import com.lifelink.app.data.local.PendingSubmissionWorker
import com.lifelink.app.data.local.toDomain
import com.lifelink.app.data.local.toEntity
import com.lifelink.app.data.remote.EmergencyRequestRequest
import com.lifelink.app.data.remote.LifeLinkApi
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.ActiveRequestStatus
import com.lifelink.app.domain.ContactMethod
import com.lifelink.app.domain.DonorRepository
import com.lifelink.app.domain.DiscoveredDonor
import com.lifelink.app.data.remote.ContactSelectedDonorsRequest
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.EmergencyRequestRepository
import com.lifelink.app.domain.Facility
import com.lifelink.app.domain.SubmitResult
import com.lifelink.app.domain.Urgency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

class EmergencyRequestRepositoryImpl(
    private val draftDao: EmergencyRequestDraftDao,
    private val pendingSubmissionDao: PendingSubmissionDao,
    private val activeRequestDao: ActiveRequestDao,
    private val api: LifeLinkApi,
    private val workManager: WorkManager
) : EmergencyRequestRepository {
    override suspend fun saveDraft(draft: EmergencyRequestDraft) = draftDao.upsert(draft.toEntity())

    override suspend fun loadDraft(id: String): EmergencyRequestDraft? = draftDao.findById(id)?.toDomain()

    override fun observeActiveRequest(): Flow<ActiveRequestSnapshot?> =
        activeRequestDao.observeLatest().map { it?.toDomain() }

    override suspend fun refreshActiveRequest(requestId: String): ActiveRequestSnapshot? = withContext(Dispatchers.IO) {
        try {
            val response = api.getEmergencyRequest(requestId)
            if (!response.isSuccessful) return@withContext null
            val body = response.body() ?: return@withContext null
            val snapshot = body.toSnapshot()
            activeRequestDao.upsert(snapshot.toEntity())
            snapshot
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun submit(draft: EmergencyRequestDraft): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val response = api.submitEmergencyRequest(draft.id, EmergencyRequestRequest.from(draft))
            if (!response.isSuccessful) {
                SubmitResult.Error("The server rejected the request (${response.code()}). Check the details and try again.")
            } else {
                val body = response.body()
                if (body == null) {
                    SubmitResult.Error("The server returned an empty response. Your draft is still saved.")
                } else if (body.status.equals("manual_broadcast", ignoreCase = true)) {
                    val fallback = SubmitResult.ManualFallback(body.requestId, body.reason ?: "Automatic matching is unavailable for this request.")
                    activeRequestDao.upsert(ActiveRequestSnapshot(body.requestId, ActiveRequestStatus.MANUAL_BROADCAST, reason = body.reason).toEntity())
                    fallback
                } else {
                    activeRequestDao.upsert(ActiveRequestSnapshot(body.requestId, ActiveRequestStatus.AWAITING_RESPONSES, body.notificationsCreated).toEntity())
                    SubmitResult.MatchingStarted(
                        body.requestId,
                        body.matches.map { match ->
                            DiscoveredDonor(match.donorId, match.displayName, match.bloodType, match.distanceKm, match.travelMinutes, match.score, match.explanation?.factors.orEmpty())
                        }
                    )
                }
            }
        } catch (_: IOException) {
            queueForRetry(draft)
            SubmitResult.OfflineQueued(draft.id)
        } catch (_: Exception) {
            SubmitResult.Error("We couldn’t reach LifeLink right now. Your draft is still saved.")
        }
    }

    override suspend fun sendManualBroadcast(requestId: String): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val response = api.sendManualBroadcast(requestId)
            if (response.isSuccessful) {
                activeRequestDao.upsert(ActiveRequestSnapshot(requestId, ActiveRequestStatus.AWAITING_RESPONSES).toEntity())
                SubmitResult.MatchingStarted(response.body()?.requestId ?: requestId)
            } else SubmitResult.Error("Manual broadcast could not be sent (${response.code()}).")
        } catch (_: IOException) {
            SubmitResult.Error("You’re offline. No manual broadcast was sent.")
        } catch (_: Exception) {
            SubmitResult.Error("Manual broadcast could not be sent. Please retry.")
        }
    }

    override suspend fun contactSelectedDonors(requestId: String, donorIds: List<String>): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val response = api.contactSelectedDonors(requestId, ContactSelectedDonorsRequest(donorIds))
            if (response.isSuccessful) SubmitResult.ContactRequested(requestId, donorIds)
            else SubmitResult.Error("Selected donors could not be contacted (${response.code()}).")
        } catch (_: IOException) {
            SubmitResult.Error("You’re offline. No donor contact request was sent.")
        } catch (_: Exception) {
            SubmitResult.Error("Selected donors could not be contacted. Please retry.")
        }
    }

    override suspend fun cancelRequest(requestId: String): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val response = api.cancelEmergencyRequest(requestId)
            if (response.isSuccessful) {
                activeRequestDao.upsert(
                    ActiveRequestSnapshot(
                        requestId = requestId,
                        status = ActiveRequestStatus.CANCELLED,
                        reason = response.body()?.reason ?: "Cancelled by coordinator"
                    ).toEntity()
                )
                SubmitResult.Cancelled(requestId)
            } else SubmitResult.Error("The request could not be cancelled (${response.code()}).")
        } catch (_: IOException) {
            SubmitResult.Error("You’re offline. The request is still active.")
        } catch (_: Exception) {
            SubmitResult.Error("The request could not be cancelled. Please retry.")
        }
    }

    private suspend fun queueForRetry(draft: EmergencyRequestDraft) {
        pendingSubmissionDao.upsert(PendingSubmissionEntity(id = draft.id, payloadJson = Gson().toJson(draft)))
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = OneTimeWorkRequestBuilder<PendingSubmissionWorker>().setConstraints(constraints).build()
        workManager.enqueueUniqueWork("lifelink-submit-${draft.id}", ExistingWorkPolicy.KEEP, work)
    }
}

class LifeLinkAppContainer(
    val emergencyRequestRepository: EmergencyRequestRepository,
    val donorRepository: DonorRepository
)

private fun EmergencyRequestDraft.toEntity() = EmergencyRequestDraftEntity(
    id = id, bloodType = bloodType?.name, units = units, typeUnknown = typeUnknown,
    urgency = urgency.name, responseDeadline = responseDeadline, note = note,
    facilityId = facility?.id, facilityName = facility?.name, facilityArea = facility?.area,
    facilityVerified = facility?.verified ?: false, contactMethod = contactMethod.name,
    genuineRequestConfirmed = genuineRequestConfirmed, sharingConsentConfirmed = sharingConsentConfirmed,
    aiMatchingEnabled = aiMatchingEnabled,
    updatedAtEpochMillis = System.currentTimeMillis()
)

private fun EmergencyRequestDraftEntity.toDomain() = EmergencyRequestDraft(
    id = id, bloodType = bloodType?.let { runCatching { com.lifelink.app.domain.BloodType.valueOf(it) }.getOrNull() }, units = units, typeUnknown = typeUnknown,
    urgency = enumValueOf<Urgency>(urgency), responseDeadline = responseDeadline, note = note,
    facility = facilityId?.let { Facility(it, facilityName.orEmpty(), facilityArea.orEmpty(), facilityVerified) },
    contactMethod = enumValueOf<ContactMethod>(contactMethod), genuineRequestConfirmed = genuineRequestConfirmed,
    sharingConsentConfirmed = sharingConsentConfirmed, aiMatchingEnabled = aiMatchingEnabled
)

private fun com.lifelink.app.data.remote.EmergencyRequestStatusResponse.toSnapshot() = ActiveRequestSnapshot(
    requestId = requestId,
    status = runCatching { ActiveRequestStatus.valueOf(status.uppercase()) }.getOrDefault(ActiveRequestStatus.MATCHING),
    notificationsCreated = notificationsCreated,
    matchesResponded = matchesResponded,
    reason = reason
)
