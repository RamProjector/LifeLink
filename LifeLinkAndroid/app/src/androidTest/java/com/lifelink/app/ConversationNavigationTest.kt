package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.navigation.LifeLinkShell
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse
import com.lifelink.app.domain.RequestStep
import com.lifelink.app.domain.RequesterContact
import com.lifelink.app.feature.donor.DonorScreen
import com.lifelink.app.feature.donor.DonorUiState
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestScreen
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState
import com.lifelink.app.feature.emergencyrequest.SubmissionState
import com.lifelink.app.feature.privacy.DonorMapScreen
import com.lifelink.app.feature.privacy.PrivacyAction
import com.lifelink.app.feature.privacy.PrivacyUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the new navigation paths and screen callbacks through real Compose interactions. */
@RunWith(AndroidJUnit4::class)
open class ConversationNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<PrivacyAction>()
    private val profile =
        DonorProfile(
            donorId = "donor-42",
            displayName = "Alex Donor",
            bloodType = BloodType.O_POS,
            latitude = 14.6,
            longitude = 121.0,
        )
    private val request = DonorRequest("request-42", "O+", 1, "urgent", "Test Hospital", "Test area", 2.0)

    /** Removes the synthetic account's welcome preference so it cannot affect later tests. */
    @After
    fun clearWelcomePreference() {
        composeRule.activity.getSharedPreferences("lifelink_welcome", 0)
            .edit()
            .remove("seen_conversation-test")
            .commit()
    }

    /** Checks requester chat navigation, state restoration, and return to the accepted contact results. */
    @Test
    fun requester_can_open_chat_restore_it_and_return_to_contact_results() {
        val restoration = StateRestorationTester(composeRule)
        renderShell(restoration, UserRole.REQUESTER)
        composeRule.onNodeWithText("Create emergency request").performScrollTo().performClick()
        composeRule.onNodeWithText("Message Alex Donor").performScrollTo().performClick()

        composeRule.onNodeWithText("Conversation").assertIsDisplayed()
        assertOpenedTimes(1)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Conversation").assertIsDisplayed()
        assertOpenedTimes(2)

        composeRule.onNodeWithText("Back", substring = false).performClick()
        composeRule.onNodeWithText("Conversation").assertDoesNotExist()
        composeRule.onNodeWithText("Message Alex Donor").performScrollTo().assertIsDisplayed()
        assertOpenedTimes(2)
    }

    /** Checks that a donor opens chat once and system Back returns to the donor workspace. */
    @Test
    fun donor_can_open_chat_and_system_back_returns_to_the_donor_workspace() {
        renderShell(StateRestorationTester(composeRule), UserRole.DONOR)
        composeRule.onNodeWithContentDescription("Profile").performClick()
        composeRule.onNodeWithTag("open-donor-workspace").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("donor-workspace-screen").fetchSemanticsNodes().isNotEmpty()
        }
        // Capture the actual destination before asserting so a regression leaves a useful
        // screenshot in the CI artifact rather than only a line-number failure.
        assertTrue(VisualEvidence.capture("lifelink-donor-workspace-after-open.png"))
        composeRule.onNodeWithTag("donor-workspace-screen").assertIsDisplayed()
        composeRule.onNodeWithText("Donor workspace", substring = false).assertIsDisplayed()
        composeRule.onNodeWithTag("donor-tab-REQUESTS").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Message requester").performScrollTo().performClick()

        composeRule.onNodeWithText("Conversation").assertIsDisplayed()
        assertOpenedTimes(1)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithText("Conversation").assertDoesNotExist()
        composeRule.onNodeWithTag("donor-tab-REQUESTS").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("Message requester").performScrollTo().assertIsDisplayed()
        assertOpenedTimes(1)
    }

    /** Checks donor message-button visibility and callback IDs across unanswered, declined, and accepted states. */
    @Test
    fun donor_message_action_is_available_only_for_accepted_or_arrived_requests() {
        val state = mutableStateOf(DonorUiState(profile = profile, requests = listOf(request)))
        val opened = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            LifeLinkTheme {
                DonorScreen(state.value, onAction = {}, onBack = {}, onOpenConversation = { requestId, donorId ->
                    opened += requestId to donorId
                })
            }
        }
        composeRule.onNodeWithText("Requests", substring = false).performClick()

        for (response in listOf(null, DonorResponse.DECLINED, DonorResponse.ACCEPTED, DonorResponse.ARRIVED)) {
            composeRule.runOnIdle { state.value = state.value.copy(requests = listOf(request.copy(response = response))) }
            composeRule.onNodeWithText("Test Hospital").performScrollTo().assertIsDisplayed()
            if (response == DonorResponse.ACCEPTED || response == DonorResponse.ARRIVED) {
                composeRule.onNodeWithText("Message requester").performScrollTo().performClick()
            } else {
                composeRule.onNodeWithText("Message requester").assertDoesNotExist()
            }
        }
        composeRule.runOnIdle { assertEquals(List(2) { "request-42" to "donor-42" }, opened) }
    }

    /** Checks requester chat eligibility across contact states, including a case-insensitive accepted status. */
    @Test
    fun requester_message_action_is_available_for_all_accepted_contact_states_only() {
        val state = mutableStateOf(contactResults("pending"))
        val opened = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            LifeLinkTheme {
                EmergencyRequestScreen(state.value, onAction = {}, onOpenConversation = { requestId, donorId ->
                    opened += requestId to donorId
                })
            }
        }
        val accepted = listOf("accepted", "arrived", "contact_shared", "meeting_arranged", "fulfilled", "ACCEPTED")
        for (status in listOf("pending", "declined", "cancelled") + accepted) {
            composeRule.runOnIdle { state.value = contactResults(status) }
            composeRule.onNodeWithText("Alex Donor", substring = false).performScrollTo().assertIsDisplayed()
            if (status in accepted) {
                composeRule.onNodeWithText("Message Alex Donor").performScrollTo().performClick()
            } else {
                composeRule.onNodeWithText("Message Alex Donor").assertDoesNotExist()
            }
        }
        composeRule.runOnIdle { assertEquals(List(accepted.size) { "request-42" to "donor-42" }, opened) }
    }

    /** Checks that chat requires a request ID and prefers the saved results ID over the matching fallback. */
    @Test
    fun requester_needs_a_request_id_and_can_use_the_matching_submission_id() {
        val state = mutableStateOf(contactResults("accepted").copy(resultsRequestId = null))
        val opened = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            LifeLinkTheme {
                EmergencyRequestScreen(state.value, onAction = {}, onOpenConversation = { requestId, donorId ->
                    opened += requestId to donorId
                })
            }
        }
        composeRule.onNodeWithText("Alex Donor", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Message Alex Donor").assertDoesNotExist()

        composeRule.runOnIdle { state.value = state.value.copy(submission = SubmissionState.Matching("fallback-request")) }
        composeRule.onNodeWithText("Message Alex Donor").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("fallback-request" to "donor-42"), opened) }

        composeRule.runOnIdle { state.value = state.value.copy(resultsRequestId = "saved-request") }
        composeRule.onNodeWithText("Message Alex Donor").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("saved-request" to "donor-42", opened.last()) }
    }

    /** Verifies unsaved radius and GPS-only controls survive full-screen editor transitions. */
    @Test
    fun unsaved_profile_radius_and_gps_controls_survive_full_screen_transitions() {
        composeRule.setContent {
            LifeLinkTheme {
                DonorScreen(DonorUiState(profile = profile), onAction = {}, onBack = {})
            }
        }
        composeRule.onNodeWithText("Edit donor profile").performScrollTo().performClick()
        replaceField("Service radius (km)", "27")
        composeRule.onNodeWithText("Update current location").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open profile in full screen").performClick()

        assertEditorValues()
        composeRule.onNodeWithText("Exit full screen", substring = false).performClick()
        assertEditorValues()
    }

    /** Scrolls the labeled input into view and replaces its text through Compose test semantics. */
    private fun replaceField(label: String, value: String) {
        composeRule.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    }

    /** Asserts the unsaved radius and GPS-only location control remain available. */
    private fun assertEditorValues() {
        composeRule.onNodeWithText("Service radius (km)").performScrollTo().assertTextContains("27")
        composeRule.onNodeWithText("Update current location").performScrollTo().assertIsDisplayed()
    }

    /** Checks on the idle UI thread that every recorded action opens the expected request and donor pair. */
    private fun assertOpenedTimes(count: Int) {
        composeRule.runOnIdle {
            assertEquals(List(count) { PrivacyAction.OpenConversation("request-42", "donor-42") }, actions)
        }
    }

    /** Builds requester results with one synthetic donor contact in the supplied status. */
    private fun contactResults(status: String) =
        EmergencyRequestUiState(
            step = RequestStep.RESULTS,
            resultsRequestId = "request-42",
            contacts = listOf(RequesterContact("donor-42", "Alex Donor", status)),
        )

    /** Verifies that the donor map is a labelled top-level destination and navigation remains available. */
    @Test
    fun donor_map_is_a_labeled_top_level_destination_and_navigation_remains_available() {
        renderShell(StateRestorationTester(composeRule), UserRole.REQUESTER)

        composeRule.onNodeWithTag("nav-MAP").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Find donors", substring = false).assertIsDisplayed()
        composeRule.onNodeWithTag("nav-MAP").assertIsSelected()
        composeRule.onNodeWithTag("nav-HOME").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close donor map").assertDoesNotExist()

        composeRule.onNodeWithTag("nav-HOME").performClick()
        composeRule.onNodeWithText("LifeLink", substring = false).assertIsDisplayed()
    }

    /** Verifies that map loading, errors, and empty results are mutually exclusive and truthful. */
    @Test
    fun donor_map_shows_the_correct_loading_error_and_empty_state() {
        val state = mutableStateOf(PrivacyUiState(mapLoading = true))
        composeRule.setContent {
            LifeLinkTheme {
                DonorMapScreen(state.value, onAction = {}, onBack = {}, mapStyleJson = SYNTHETIC_MAP_STYLE_JSON)
            }
        }

        composeRule.onNodeWithText("Loading donor areas…").assertIsDisplayed()
        composeRule.onNodeWithText("No donor areas available").assertDoesNotExist()
        composeRule.onNodeWithText("0 areas").assertDoesNotExist()

        composeRule.runOnIdle { state.value = PrivacyUiState(mapError = "Could not load donor areas.") }
        composeRule.onNodeWithText("Could not load donor areas.").assertIsDisplayed()
        composeRule.onNodeWithText("No donor areas available").assertDoesNotExist()
        composeRule.onNodeWithText("0 areas").assertDoesNotExist()

        composeRule.runOnIdle { state.value = PrivacyUiState() }
        composeRule.onNodeWithText("No donor areas available").assertIsDisplayed()
    }

    /** Uses only a local synthetic fixture to verify list rendering and both donor-map filters. */
    @Test
    fun donor_map_synthetic_fixture_filters_areas_without_live_data() {
        composeRule.setContent {
            LifeLinkTheme {
                DonorMapScreen(
                    state = SyntheticDonorMapFixture.state,
                    onAction = {},
                    onBack = {},
                    mapStyleJson = SYNTHETIC_MAP_STYLE_JSON,
                )
            }
        }

        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-map-area-list").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area A").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area B").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Show map filters").performClick()
        composeRule.onNodeWithText("O+", substring = false).performClick()
        composeRule.onNodeWithText("1 matching areas", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area A").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area B").assertDoesNotExist()

        composeRule.onNodeWithText("Clear filters", substring = false).performClick()
        composeRule.onNodeWithText("Synthetic Test Area B").assertIsDisplayed()
        composeRule.onNodeWithText("Available now", substring = false).performClick()
        composeRule.onNodeWithText("1 matching areas", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area A").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area B").assertDoesNotExist()
    }

    /** Renders a restorable shell for the supplied role, skips welcome, and records privacy actions. */
    private fun renderShell(restoration: StateRestorationTester, role: UserRole) {
        composeRule.activity.getSharedPreferences("lifelink_welcome", 0)
            .edit()
            .putBoolean("seen_conversation-test", true)
            .commit()
        restoration.setContent {
            LifeLinkTheme {
                LifeLinkShell(
                    state = contactResults("accepted"),
                    onAction = {},
                    donorState = DonorUiState(profile = profile, requests = listOf(request.copy(response = DonorResponse.ACCEPTED))),
                    onDonorAction = {},
                    becomeDonorState = com.lifelink.app.feature.donor.BecomeDonorUiState(loading = false),
                    onBecomeDonorAction = {},
                    privacyState = PrivacyUiState(),
                    onPrivacyAction = { actions += it },
                    role = role,
                    onSwitchRole = {},
                    accountEmail = "conversation@example.invalid",
                    accountUserId = "conversation-test",
                    accountDisplayName = "Test User",
                    profileSaving = false,
                    profileMessage = null,
                    onSaveProfile = {},
                    themeMode = ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onRequestPasswordReset = {},
                    onSignOut = {},
                    updates = emptyList(),
                    onUpdateRead = {},
                    onMarkAllUpdatesRead = {},
                    donorMapStyleJson = SYNTHETIC_MAP_STYLE_JSON,
                )
            }
        }
    }
}
