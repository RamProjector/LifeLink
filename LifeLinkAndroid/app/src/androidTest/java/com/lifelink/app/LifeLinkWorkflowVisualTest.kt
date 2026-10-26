package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.navigation.LifeLinkShell
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import com.lifelink.app.domain.ActiveRequestStatus
import com.lifelink.app.domain.DonorMap
import com.lifelink.app.domain.DonorMapArea
import com.lifelink.app.domain.RequestHistoryItem
import com.lifelink.app.feature.donor.DonorUiState
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState
import com.lifelink.app.feature.privacy.PrivacyUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeLinkWorkflowVisualTest {
    // Synthetic-only map fixture: these coordinates and labels are not tied to donor accounts,
    // are never sent to a backend, and exist solely to exercise map rendering and local filters.
    private val syntheticDonorMap = DonorMap(
        generatedAt = "2026-10-26T00:00:00Z",
        approximateOnly = true,
        areas = listOf(
            DonorMapArea(
                areaLabel = "Synthetic demo area — test fixture",
                latitude = 37.7749,
                longitude = -122.4194,
                radiusMeters = 5_000,
                bloodType = "O+",
                availability = "Available",
                freshnessAt = "2026-10-26T00:00:00Z",
                freshnessAgeMinutes = 2,
                isStale = false,
            ),
            DonorMapArea(
                areaLabel = "Synthetic unavailable area — test fixture",
                latitude = 37.7849,
                longitude = -122.4094,
                radiusMeters = 5_000,
                bloodType = "A+",
                availability = "Unavailable",
                freshnessAt = "2026-10-26T00:00:00Z",
                freshnessAgeMinutes = 4,
                isStale = false,
            ),
        ),
    )
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        state: EmergencyRequestUiState = EmergencyRequestUiState(),
        updates: List<com.lifelink.app.domain.UpdateItem> = emptyList(),
        privacyState: PrivacyUiState = PrivacyUiState(),
        role: UserRole = UserRole.REQUESTER,
        onAction: (com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction) -> Unit = {},
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

    /** Verifies navigation through requests, notifications, settings, and donor setup, capturing each screen. */
    @Test
    fun navigatesCoreWorkflowsAndCapturesEvidence() {
        render(privacyState = PrivacyUiState(map = syntheticDonorMap))
        assertVisible("Your requests")
        capture("workflow-home")

        composeRule.onNodeWithTag("nav-MAP").performClick()
        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show map filters").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-donor-map-view").assertIsDisplayed()
        capture("workflow-donor-map-loading")
        // Wait for MapLibre's full-render callback, not Compose idleness alone. A parsed style
        // can still leave the native map blank while vector tiles are being fetched.
        runCatching {
            composeRule.waitUntil(timeoutMillis = 45_000) {
                composeRule.onAllNodesWithTag("maplibre-map-rendered").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithTag("maplibre-map-error").fetchSemanticsNodes().isNotEmpty()
            }
        }
        val mapRendered = composeRule.onAllNodesWithTag("maplibre-map-rendered").fetchSemanticsNodes().isNotEmpty()
        capture(if (mapRendered) "workflow-donor-map" else "workflow-donor-map-render-error")
        composeRule.onNodeWithTag("maplibre-map-rendered").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-map-area-list").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        tapTab("Requests")
        assertVisible("No active request")
        capture("workflow-requests")

        composeRule.onNodeWithTag("open-notifications").performClick()
        composeRule.waitForIdle()
        assertVisible("Nothing needs your attention")
        capture("workflow-updates")

        // The notifications overlay replaces the shell (and its bottom navigation),
        // so dismiss it before navigating to another tab.
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

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
    fun syntheticDonorMapDemoFiltersLocallyWithoutLiveDonorData() {
        render(privacyState = PrivacyUiState(map = syntheticDonorMap))
        composeRule.onNodeWithTag("nav-MAP").performClick()
        composeRule.onNodeWithText("2 areas", substring = false).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show map filters").performClick()

        composeRule.onNodeWithText("O+", substring = false).performClick()
        composeRule.onNodeWithText("1 matching areas", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("Clear filters", substring = false).performClick()
        composeRule.onNodeWithText("Available now", substring = false).performClick()
        composeRule.onNodeWithText("1 matching areas", substring = false).assertIsDisplayed()

        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithText("Synthetic demo area — test fixture", substring = false)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic unavailable area — test fixture", substring = false)
            .assertDoesNotExist()
        composeRule.onNodeWithText("Approximate donor pins only", substring = false).assertIsDisplayed()
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
        VisualEvidence.capture("lifelink-$scenario-${testTheme.name.lowercase()}-$name.png")
    }
}
