package com.lifelink.app

import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRepository
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse
import com.lifelink.app.feature.donor.DonorAction
import com.lifelink.app.feature.donor.DonorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun gps_update_does_not_replace_unsaved_profile_fields_with_stale_row() = runTest {
        val persisted = MutableStateFlow(
            DonorProfile(
                displayName = "Alex Donor",
                area = "Quezon City",
                bloodType = BloodType.O_NEG,
                serviceRadiusKm = 25,
                availability = DonorAvailability.AVAILABLE
            )
        )
        val repository = FakeDonorRepository(persisted)
        val viewModel = DonorViewModel(repository, enablePolling = false)

        viewModel.onAction(DonorAction.SetLocation(14.6466, 121.0437, 12))
        persisted.value = DonorProfile()

        val profile = viewModel.state.value.profile
        assertEquals("Alex Donor", profile.displayName)
        assertEquals("Quezon City", profile.area)
        assertEquals(BloodType.O_NEG, profile.bloodType)
        assertEquals(25, profile.serviceRadiusKm)
        assertEquals(14.6466, profile.latitude!!, 0.000001)
        assertTrue(viewModel.state.value.profileDirty)
    }
}

private class FakeDonorRepository(
    private val persisted: MutableStateFlow<DonorProfile>
) : DonorRepository {
    override fun observeProfile(): Flow<DonorProfile> = persisted
    override fun observeRequests(): Flow<List<DonorRequest>> = flowOf(emptyList())
    override suspend fun saveProfile(profile: DonorProfile) = Unit
    override suspend fun refresh() = Unit
    override suspend fun respond(requestId: String, response: DonorResponse): Result<Unit> = Result.success(Unit)
}
