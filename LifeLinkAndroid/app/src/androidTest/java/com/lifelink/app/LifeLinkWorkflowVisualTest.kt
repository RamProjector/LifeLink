package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.location.MapLibreLocationPicker
import com.lifelink.app.core.navigation.LifeLinkShell
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import com.lifelink.app.domain.ActiveRequestStatus
import com.lifelink.app.domain.DonorMap
import com.lifelink.app.domain.DonorMapArea
import com.lifelink.app.domain.RequestHistoryItem
import com.lifelink.app.feature.donor.BecomeDonorScreen
import com.lifelink.app.feature.donor.BecomeDonorUiState
import com.lifelink.app.feature.donor.DonorScreen
import com.lifelink.app.feature.donor.DonorUiState
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState
import com.lifelink.app.feature.privacy.DonorMapScreen
import com.lifelink.app.feature.privacy.PrivacyUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class LifeLinkWorkflowVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        state: EmergencyRequestUiState = EmergencyRequestUiState(),
        updates: List<com.lifelink.app.domain.UpdateItem> = emptyList(),
        role: UserRole = UserRole.REQUESTER,
        onAction: (com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction) -> Unit = {},
        privacyState: PrivacyUiState = PrivacyUiState(),
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("lifelink_welcome", 0)
            .edit()
            .putBoolean("seen_visual-account", true)
            .commit()

        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    LifeLinkShell(
                        state = state,
                        onAction = onAction,
                        donorState = DonorUiState(),
                        onDonorAction = {},
                        becomeDonorState = com.lifelink.app.feature.donor.BecomeDonorUiState(loading = false),
                        onBecomeDonorAction = {},
                        privacyState = privacyState,
                        onPrivacyAction = {},
                        role = role,
                        onSwitchRole = {},
                        accountEmail = "visual-test@example.invalid",
                        accountUserId = "visual-account",
                        accountDisplayName = "Visual Test",
                        profileSaving = false,
                        profileMessage = null,
                        onSaveProfile = {},
                        themeMode = testTheme,
                        onThemeModeChange = {},
                        onRequestPasswordReset = {},
                        onSignOut = {},
                        updates = updates,
                        onUpdateRead = {},
                        onMarkAllUpdatesRead = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
    }

    private val testTheme: ThemeMode
        get() = if (InstrumentationRegistry.getArguments().getString("theme") == "dark") ThemeMode.DARK else ThemeMode.LIGHT

    @Test
    fun locationPickerReportsReadyOnlyAfterFullyRenderedMap() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    MapLibreLocationPicker(
                        latitude = null,
                        longitude = null,
                        onLocationSelected = { _, _ -> },
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 70_000) {
            composeRule.onAllNodesWithContentDescription("Location map rendered")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Location map unavailable")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertTrue(
            "Location picker reported a map error instead of a fully rendered map",
            composeRule.onAllNodesWithContentDescription("Location map unavailable")
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithContentDescription("Location map rendered").assertIsDisplayed()
    }

    @Test
    fun donorProfileRequiresFreshGpsAndDoesNotOfferManualCoordinates() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    BecomeDonorScreen(
                        state = BecomeDonorUiState(loading = false),
                        onAction = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("donor-gps-location").assertIsDisplayed()
        composeRule.onNodeWithTag("capture-donor-gps").assertIsDisplayed()
        org.junit.Assert.assertTrue(composeRule.onAllNodesWithText("Latitude").fetchSemanticsNodes().isEmpty())
        org.junit.Assert.assertTrue(composeRule.onAllNodesWithText("Longitude").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("donor-save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun legacyDonorWorkspaceAlsoRequiresFreshGpsAndHasNoManualCoordinates() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    DonorScreen(state = DonorUiState(), onAction = {}, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit donor profile").performClick()
        composeRule.onNodeWithText("Use my current location").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertTrue(composeRule.onAllNodesWithText("Latitude").fetchSemanticsNodes().isEmpty())
        org.junit.Assert.assertTrue(composeRule.onAllNodesWithText("Longitude").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("donor-profile-save").assertIsNotEnabled()
    }

    @Test
    fun selectingDonorAreaOpensPrivacySafeDetailsAndReturnsToMap() {
        val mapState = PrivacyUiState(
            map = DonorMap(
                generatedAt = "2026-10-28T00:00:00Z",
                approximateOnly = true,
                areas = listOf(
                    DonorMapArea(
                        areaLabel = "Approximate donor area",
                        latitude = 11.24,
                        longitude = 125.0,
                        radiusMeters = 1000,
                        bloodType = "O+",
                        availability = "available",
                        freshnessAt = "2026-10-28T00:00:00Z",
                        freshnessAgeMinutes = 2,
                        isStale = false,
                    ),
                ),
            ),
        )
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    DonorMapScreen(state = mapState, onAction = {}, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-area-card")
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithTag("donor-area-card").performClick()
        assertVisible("Donor area details")
        assertVisible("Blood type: O+")
        assertVisible(
            "Privacy protected: this view shows an approximate area only. " +
                "Donor identity, contact details, and exact coordinates are not disclosed here.",
        )
        composeRule.onNodeWithTag("donor-area-back")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
        composeRule.onNodeWithTag("donor-area-back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
    }

    @Test
    fun donorMapVisibilitySwitchRowsMeetMinimumTouchTarget() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    DonorMapScreen(
                        state = PrivacyUiState(),
                        onAction = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Map privacy controls").performClick()
        composeRule.onNodeWithTag("donor-map-privacy-panel")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Donor map privacy controls",
                ),
            )
        composeRule.onNodeWithText("Show me on the donor map").assertIsDisplayed()
        composeRule.onNodeWithTag("donor-visibility-map-row")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun donorMapErrorKeepsMapVisibleAndRetryable() {
        val actions = mutableListOf<com.lifelink.app.feature.privacy.PrivacyAction>()
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    DonorMapScreen(
                        state = PrivacyUiState(mapError = "Check your connection and try again."),
                        onAction = { actions += it },
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        composeRule.onNodeWithTag("donor-map-native-view").assertIsDisplayed()
        composeRule.onNodeWithTag("donor-map-error-feedback").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn’t load donor areas").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        org.junit.Assert.assertEquals(
            com.lifelink.app.feature.privacy.PrivacyAction.LoadMap,
            actions.last(),
        )
        capture("donor-map-error")
    }

    @Test
    fun emptyDonorMapKeepsMapVisibleAndOffersRefresh() {
        val actions = mutableListOf<com.lifelink.app.feature.privacy.PrivacyAction>()
        val emptyMapState = PrivacyUiState(
            map = DonorMap(
                generatedAt = "2026-10-30T00:00:00Z",
                approximateOnly = true,
                areas = emptyList(),
            ),
        )
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = testTheme) {
                    DonorMapScreen(
                        state = emptyMapState,
                        onAction = { actions += it },
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        composeRule.onNodeWithTag("donor-map-native-view").assertIsDisplayed()
        composeRule.onNodeWithText("No donor areas available").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh areas").assertIsDisplayed()
        capture("donor-map-empty")
    }

    /** Verifies navigation through requests, notifications, settings, and donor setup, capturing each screen. */
    @Test
    fun navigatesCoreWorkflowsAndCapturesEvidence() {
        render(privacyState = SyntheticDonorMapFixture.state)
        assertVisible("Your requests")
        capture("workflow-home")
        verifyDonorMapWorkflow()
        verifyRequestsAndNotifications()
        verifyProfileSettings()
    }

    private fun verifyDonorMapWorkflow() {
        composeRule.onNodeWithTag("nav-MAP").performClick()
        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show map filters").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-donor-map-view").assertIsDisplayed()
        // Switching to List disposes MapView, so wait for actual tile rendering first.
        composeRule.waitUntil(timeoutMillis = 70_000) {
            composeRule.onAllNodesWithContentDescription("Donor map rendered")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription("Donor map unavailable")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        // Tile providers are external and may be unreachable in CI. Both the
        // fully rendered map and the explicit unavailable state are valid UI
        // outcomes; the screen controls and recovery path must remain usable.
        val mapRendered = composeRule.onAllNodesWithContentDescription("Donor map rendered")
            .fetchSemanticsNodes().isNotEmpty()
        val mapUnavailable = composeRule.onAllNodesWithContentDescription("Donor map unavailable")
            .fetchSemanticsNodes().isNotEmpty()
        org.junit.Assert.assertTrue("Map must report either rendered or unavailable", mapRendered || mapUnavailable)
        composeRule.onNodeWithText("2 areas", substring = false).assertIsDisplayed()
        capture("workflow-donor-map")

        // Filter-result changes are announced politely instead of being silent visual-only updates.
        composeRule.onNodeWithContentDescription("Show map filters").performClick()
        composeRule.onNodeWithTag("donor-map-filter-panel")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Donor map filters",
                ),
            )
        composeRule.onNodeWithTag("donor-map-filter-count")
            .assertIsDisplayed()
            .assert(hasText("2 matching areas", substring = false))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText("Available now")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("O+")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag("donor-map-filter-count")
            .assert(hasText("1 matching area", substring = false))
        composeRule.onNodeWithText("1 area", substring = false).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide map filters, 2 active filters").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide map filters, 2 active filters").performClick()
        composeRule.onNodeWithContentDescription("Show map filters, 2 active filters").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show map filters, 2 active filters").performClick()
        composeRule.onNodeWithText("Clear filters").performClick()
        composeRule.onNodeWithTag("donor-map-filter-count")
            .assert(hasText("2 matching areas", substring = false))
        composeRule.onNodeWithText("Clear filters").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Hide map filters").performClick()

        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-map-area-list").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area A").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic Test Area B").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun verifyRequestsAndNotifications() {
        tapTab("Requests")
        assertVisible("No active request")
        capture("workflow-requests")

        composeRule.onNodeWithTag("open-notifications").performClick()
        composeRule.waitForIdle()
        assertVisible("Nothing needs your attention")
        capture("workflow-updates")

        // Dismiss the overlay before navigating because it replaces bottom navigation.
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun verifyProfileSettings() {
        composeRule.onNodeWithTag("open-profile").performClick()
        composeRule.waitForIdle()
        assertVisible("Account, privacy, and LifeLink information.")

        tapTab("Profile")
        assertVisible("Account, privacy, and LifeLink information.")
        capture("workflow-settings-profile")

        tap("Legal")
        assertVisible("Legal Documents")
        composeRule.onNodeWithText("Privacy Policy").performClick()
        assertVisible("What this app does")
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Terms and Conditions").performClick()
        assertVisible("Emergency and medical limitation")
        composeRule.onNodeWithText("Close").performClick()

        tap("Theme")
        assertVisible("Use device setting")
        capture("workflow-settings-theme")

        tap("Security")
        assertVisible("Reset password")
        capture("workflow-settings-security")
    }

    @Test
    fun homeAdaptsToWindowSize() {
        render()
        val widthDp = composeRule.activity.resources.configuration.screenWidthDp
        if (widthDp >= 600) {
            composeRule.onNodeWithTag("navigation-rail").assertIsDisplayed()
        } else {
            org.junit.Assert.assertTrue(composeRule.onAllNodesWithTag("navigation-rail").fetchSemanticsNodes().isEmpty())
        }
        assertVisible("Your requests")
        capture("home-adaptive")
        composeRule.onNodeWithText("Create emergency request").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun homeOpensRequestFormDirectly() {
        render()
        composeRule.onNodeWithText("Create emergency request").performScrollTo().performClick()
        assertVisible("Blood need")
        capture("request-form")
    }

    @Test
    fun returningDonorLandsOnHome() {
        render(role = UserRole.DONOR)
        assertVisible("Your requests")
    }

    @Test
    fun homeLinksOpenRequestsAndActivity() {
        render(
            updates = listOf(
                com.lifelink.app.domain.UpdateItem(
                    id = "visual-update",
                    type = com.lifelink.app.domain.UpdateType.DONOR_RESPONSE,
                    title = "A donor responded",
                    body = "Open your request to see the response.",
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            ),
        )
        composeRule.onNodeWithText("A donor responded").performScrollTo().performClick()
        assertVisible("Activity")
        assertVisible("A donor responded")
    }

    @Test
    fun failedHistoryLoadShowsRetryEvenWhenThereAreNoCachedRequests() {
        val actions = mutableListOf<com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction>()
        render(state = EmergencyRequestUiState(historyError = "Couldn\u2019t load your requests."), onAction = { actions += it })
        tapTab("Requests")
        assertVisible("Requests unavailable")
        org.junit.Assert.assertTrue(composeRule.onAllNodesWithText("No active request").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
        org.junit.Assert.assertEquals(listOf(com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction.RefreshHistory), actions)
    }

    @Test
    fun historyCanReachLastRequest() {
        render(
            EmergencyRequestUiState(
                requestHistory = List(30) { index ->
                    RequestHistoryItem("history-$index", ActiveRequestStatus.FULFILLED, bloodType = "O+", units = 2)
                },
            ),
        )
        tapTab("Requests")
        composeRule.onNodeWithTag("request-history").performScrollToNode(hasText("Request history-29"))
        assertVisible("Request history-29")
        capture("request-history")
    }

    private fun tapTab(label: String) {
        composeRule.onNodeWithTag("nav-${label.uppercase()}").performClick()
        composeRule.waitForIdle()
    }

    private fun tap(label: String) {
        composeRule.onNodeWithTag("settings-${label.uppercase()}").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun assertVisible(label: String) {
        composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val scenario = InstrumentationRegistry.getArguments().getString("scenario", "phone")
        org.junit.Assert.assertTrue(
            "Required screenshot capture failed: $name",
            VisualEvidence.capture("lifelink-$scenario-${testTheme.name.lowercase()}-$name.png"),
        )
    }
}
