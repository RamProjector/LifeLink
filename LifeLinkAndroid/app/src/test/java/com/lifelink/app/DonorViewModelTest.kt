package com.lifelink.app

import androidx.lifecycle.ViewModelStore
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRepository
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse
import com.lifelink.app.feature.donor.DonorAction
import com.lifelink.app.feature.donor.DonorViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DonorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun saving_a_blood_type_change_refreshes_the_inbox_immediately() = runTest {
        val persisted = MutableStateFlow(
            DonorProfile(
                displayName = "Alex Donor",
                area = "Quezon City",
                bloodType = BloodType.A_POS,
                serviceRadiusKm = 25,
                availability = DonorAvailability.AVAILABLE,
            ),
        )
        val repository = CountingRefreshDonorRepository(persisted)
        val viewModel = DonorViewModel(repository, enablePolling = false)

        viewModel.onAction(DonorAction.UpdateDraft { it.copy(bloodType = BloodType.O_POS) })
        viewModel.onAction(DonorAction.SaveProfile)

        // A blood-type change must re-evaluate matching at once, not on the next poll.
        assertEquals(1, repository.refreshCount)
    }

    @Test
    fun gps_update_does_not_replace_unsaved_profile_fields_with_stale_row() = runTest {
        val persisted = MutableStateFlow(
            DonorProfile(
                displayName = "Alex Donor",
                area = "Quezon City",
                bloodType = BloodType.O_NEG,
                serviceRadiusKm = 25,
                availability = DonorAvailability.AVAILABLE,
            ),
        )
        val repository = FakeDonorRepository(persisted)
        val viewModel = DonorViewModel(repository, enablePolling = false)

        viewModel.onAction(
            DonorAction.UpdateDraft {
                it.copy(displayName = "Edited donor", area = "Makati", bloodType = BloodType.A_POS, serviceRadiusKm = 15)
            },
        )
        viewModel.onAction(DonorAction.SetLocation(14.6466, 121.0437, 12))
        persisted.value = DonorProfile()

        val profile = viewModel.state.value.profile
        assertEquals("Edited donor", profile.displayName)
        assertEquals("Makati", profile.area)
        assertEquals(BloodType.A_POS, profile.bloodType)
        assertEquals(15, profile.serviceRadiusKm)
        assertEquals(14.6466, profile.latitude!!, 0.000001)
        assertTrue(viewModel.state.value.profileDirty)
    }

    @Test
    fun accept_response_survives_view_model_store_being_cleared_mid_write() = runTest {
        val started = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var wroteResponse = false
        val repository = SlowRespondDonorRepository(started, releaseWrite) { wroteResponse = true }
        // Stands in for MainActivity's applicationScope-backed launchAccountWrite: a scope
        // with no relationship to the ViewModel's own viewModelScope.
        val durableScope = CoroutineScope(Job())
        lateinit var durableJob: Job
        val viewModel = DonorViewModel(
            repository = repository,
            enablePolling = false,
            launchDurableWrite = { block ->
                durableJob = durableScope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
            },
        )
        // A real ViewModelStore, so .clear() below exercises the exact teardown path
        // MainActivity's DisposableEffect triggers on sign-out (accountModels.viewModelStore.clear()).
        val store = ViewModelStore().apply { put("donor", viewModel) }

        viewModel.onAction(DonorAction.Respond("req-1", DonorResponse.ACCEPTED))
        started.await()

        // Simulate a sign-out landing a split second after the tap.
        store.clear()

        releaseWrite.complete(Unit)
        durableJob.join()

        assertTrue("the response should still reach the repository after the store is cleared", wroteResponse)
    }
}

private class SlowRespondDonorRepository(
    private val started: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
    private val onRespond: () -> Unit,
) : DonorRepository {
    override fun observeProfile(): Flow<DonorProfile> = flowOf(DonorProfile())
    override fun observeRequests(): Flow<List<DonorRequest>> = flowOf(emptyList())
    override suspend fun saveProfile(profile: DonorProfile) = Unit
    override suspend fun setAvailability(availability: DonorAvailability) = Unit
    override suspend fun refresh() = Unit
    override suspend fun respond(requestId: String, response: DonorResponse): Result<Unit> {
        started.complete(Unit)
        release.await()
        onRespond()
        return Result.success(Unit)
    }
}

private class FakeDonorRepository(private val persisted: MutableStateFlow<DonorProfile>) : DonorRepository {
    override fun observeProfile(): Flow<DonorProfile> = persisted
    override fun observeRequests(): Flow<List<DonorRequest>> = flowOf(emptyList())
    override suspend fun saveProfile(profile: DonorProfile) = Unit
    override suspend fun setAvailability(availability: DonorAvailability) = Unit
    override suspend fun refresh() = Unit
    override suspend fun respond(requestId: String, response: DonorResponse): Result<Unit> = Result.success(Unit)
}

private class CountingRefreshDonorRepository(private val persisted: MutableStateFlow<DonorProfile>) : DonorRepository {
    var refreshCount = 0

    override fun observeProfile(): Flow<DonorProfile> = persisted
    override fun observeRequests(): Flow<List<DonorRequest>> = flowOf(emptyList())
    override suspend fun saveProfile(profile: DonorProfile) = Unit
    override suspend fun setAvailability(availability: DonorAvailability) = Unit
    override suspend fun refresh() {
        refreshCount++
    }
    override suspend fun respond(requestId: String, response: DonorResponse): Result<Unit> = Result.success(Unit)
}
