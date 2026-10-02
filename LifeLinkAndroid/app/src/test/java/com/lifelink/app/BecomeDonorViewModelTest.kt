package com.lifelink.app

import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfileMe
import com.lifelink.app.domain.DonorProfileRepository
import com.lifelink.app.feature.donor.BecomeDonorAction
import com.lifelink.app.feature.donor.BecomeDonorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BecomeDonorViewModelTest {
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
    fun a_user_without_a_donor_profile_is_not_opted_in() = runTest {
        val viewModel = BecomeDonorViewModel(FakeDonorProfileRepository(existing = null))

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.optedIn)
    }

    @Test
    fun saving_a_complete_profile_opts_the_user_in() = runTest {
        val repository = FakeDonorProfileRepository(existing = null)
        val viewModel = BecomeDonorViewModel(repository)

        viewModel.onAction(
            BecomeDonorAction.UpdateDraft {
                it.copy(bloodType = BloodType.O_POS, latitude = 11.24, longitude = 125.0, serviceRadiusKm = 15)
            },
        )
        viewModel.onAction(BecomeDonorAction.Save)

        assertTrue(viewModel.state.value.optedIn)
        assertEquals(1, repository.saveCount)
        assertEquals(BloodType.O_POS, viewModel.state.value.draft.bloodType)
    }

    @Test
    fun saving_an_incomplete_profile_is_rejected_without_calling_the_server() = runTest {
        val repository = FakeDonorProfileRepository(existing = null)
        val viewModel = BecomeDonorViewModel(repository)

        viewModel.onAction(BecomeDonorAction.UpdateDraft { it.copy(bloodType = BloodType.O_POS) })
        viewModel.onAction(BecomeDonorAction.Save)

        assertEquals(0, repository.saveCount)
        assertFalse(viewModel.state.value.optedIn)
        assertTrue(viewModel.state.value.message!!.contains("blood type and location"))
    }

    @Test
    fun an_unverified_donor_is_told_they_will_not_be_matched_yet() = runTest {
        val repository = FakeDonorProfileRepository(existing = null, verified = false)
        val viewModel = BecomeDonorViewModel(repository)

        viewModel.onAction(
            BecomeDonorAction.UpdateDraft {
                it.copy(bloodType = BloodType.O_POS, latitude = 11.24, longitude = 125.0)
            },
        )
        viewModel.onAction(BecomeDonorAction.Save)

        assertTrue(viewModel.state.value.message!!.contains("verify"))
        assertFalse(viewModel.state.value.draft.isMatchable)
    }

    @Test
    fun a_verified_available_donor_is_matchable() = runTest {
        val repository = FakeDonorProfileRepository(existing = null, verified = true)
        val viewModel = BecomeDonorViewModel(repository)

        viewModel.onAction(
            BecomeDonorAction.UpdateDraft {
                it.copy(bloodType = BloodType.O_POS, latitude = 11.24, longitude = 125.0)
            },
        )
        viewModel.onAction(BecomeDonorAction.Save)
        viewModel.onAction(BecomeDonorAction.SetAvailability(DonorAvailability.AVAILABLE))

        assertTrue(viewModel.state.value.draft.isMatchable)
    }

    @Test
    fun opting_out_clears_the_profile() = runTest {
        val repository =
            FakeDonorProfileRepository(existing = DonorProfileMe(bloodType = BloodType.O_POS, latitude = 11.24, longitude = 125.0))
        val viewModel = BecomeDonorViewModel(repository)
        assertTrue(viewModel.state.value.optedIn)

        viewModel.onAction(BecomeDonorAction.OptOut)

        assertFalse(viewModel.state.value.optedIn)
        assertEquals(1, repository.optOutCount)
        assertNull(viewModel.state.value.draft.bloodType)
    }
}

private class FakeDonorProfileRepository(private val existing: DonorProfileMe?, private val verified: Boolean = false) :
    DonorProfileRepository {
    var saveCount = 0
    var optOutCount = 0
    private var current: DonorProfileMe? = existing

    override suspend fun load(): DonorProfileMe? = current

    override suspend fun save(profile: DonorProfileMe): DonorProfileMe {
        saveCount++
        val saved = profile.copy(verified = verified)
        current = saved
        return saved
    }

    override suspend fun setAvailability(availability: DonorAvailability): DonorProfileMe {
        val updated = (current ?: DonorProfileMe()).copy(availability = availability, verified = verified)
        current = updated
        return updated
    }

    override suspend fun optOut() {
        optOutCount++
        current = null
    }
}
