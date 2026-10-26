package com.lifelink.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.test.uiautomator.UiDevice
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
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeLinkWorkflowVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun render(
        state: EmergencyRequestUiState = EmergencyRequestUiState(),
        updates: List<com.lifelink.app.domain.UpdateItem> = emptyList(),
        role: UserRole = UserRole.REQUESTER,
        privacyState: PrivacyUiState = PrivacyUiState(),
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
        render(privacyState = syntheticMapState())
        assertVisible("Your requests")
        capture("workflow-home")

        exerciseSyntheticMap()

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

    /** Exercises real map rendering and filter recovery with only local synthetic donor areas. */
    private fun exerciseSyntheticMap() {
        composeRule.onNodeWithTag("nav-MAP").performClick()
        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show map filters").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-donor-map-view").assertIsDisplayed()
        awaitMapRender()
        // Capture through the same UiDevice path as uploaded evidence before asserting native pin pixels.
        capture("workflow-donor-map-synthetic")
        val unfilteredPinPixels = awaitDonorPinPixels(
            minimumCount = 800,
            failureMessage = "Expected two distinct synthetic donor pins to be visible.",
        )
        assertTrue(
            "Expected two distinct synthetic donor pins to be visible; found $unfilteredPinPixels matching pixels.",
            unfilteredPinPixels >= 800,
        )

        composeRule.onNodeWithContentDescription("Show map filters").performClick()
        composeRule.onNodeWithText("Available now", substring = false).performClick()
        composeRule.onNodeWithText("A-", substring = false).performClick()
        composeRule.onNodeWithContentDescription("Hide map filters").performClick()
        composeRule.onNodeWithText("No areas match these filters", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("Clear filters", substring = false).performClick()

        composeRule.onNodeWithContentDescription("Show map filters").performClick()
        composeRule.onNodeWithText("Available now", substring = false).performClick()
        composeRule.onNodeWithText("O+", substring = false).performClick()
        composeRule.onNodeWithContentDescription("Hide map filters").performClick()
        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-map-area-list").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic O+ area", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("O+ · Available", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic A- area", substring = false).assertDoesNotExist()
        capture("workflow-donor-map-filtered-list")

        composeRule.onNodeWithTag("toggle-donor-map-view").performClick()
        composeRule.onNodeWithTag("donor-map-fullscreen").assertIsDisplayed()
        awaitMapRender()
        val filteredPinPixels = awaitDonorPinPixels(
            minimumCount = 1,
            maximumCountExclusive = unfilteredPinPixels * 0.75,
            failureMessage = "Filtering to available O+ areas should remove the unavailable synthetic pin.",
        )
        assertTrue(
            "Filtering to available O+ areas should remove the unavailable synthetic pin; " +
                "before=$unfilteredPinPixels pixels, after=$filteredPinPixels pixels.",
            filteredPinPixels > 0 && filteredPinPixels < unfilteredPinPixels * 0.75,
        )
        capture("workflow-donor-map-filtered")
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    /** Waits for MapLibre's loaded-map callback; Compose idleness alone does not mean tiles rendered. */
    private fun awaitMapRender() {
        composeRule.waitUntil(timeoutMillis = 35_000) {
            composeRule.onAllNodesWithTag("donor-map-render-ready").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("donor-map-render-error").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("donor-map-render-ready").assertIsDisplayed()
    }

    /**
     * Counts the custom donor-pin color in the map viewport, excluding the status and bottom-navigation bars.
     * This verifies native marker pixels, not merely the Compose loading state or map-style callback.
     */
    private fun awaitDonorPinPixels(
        minimumCount: Int,
        maximumCountExclusive: Double = Double.POSITIVE_INFINITY,
        failureMessage: String,
    ): Int {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var lastCount = 0
        do {
            lastCount = countVisibleDonorPinPixels()
            if (lastCount >= minimumCount && lastCount < maximumCountExclusive) return lastCount
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("$failureMessage Last observed matching pixels: $lastCount.")
    }

    private fun countVisibleDonorPinPixels(): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshotFile = File(instrumentation.targetContext.cacheDir, "lifelink-donor-pin-pixel-check.png")
        screenshotFile.delete()
        check(UiDevice.getInstance(instrumentation).takeScreenshot(screenshotFile)) {
            "Could not capture the emulator screen to verify donor pins."
        }
        val screenshot = requireNotNull(BitmapFactory.decodeFile(screenshotFile.absolutePath)) {
            "Could not decode the emulator screenshot to verify donor pins."
        }
        try {
            var matchingPixels = 0
            val startX = screenshot.width / 8
            val endX = screenshot.width * 7 / 8
            val startY = screenshot.height / 12
            val endY = screenshot.height * 3 / 4
            for (y in startY until endY) {
                matchingPixels += countDonorPinPixelsInRow(screenshot, y, startX, endX)
            }
            return matchingPixels
        } finally {
            screenshot.recycle()
            screenshotFile.delete()
        }
    }

    private fun countDonorPinPixelsInRow(screenshot: Bitmap, y: Int, startX: Int, endX: Int): Int {
        var matchingPixels = 0
        for (x in startX until endX) {
            if (isDonorPinPixel(screenshot.getPixel(x, y))) matchingPixels++
        }
        return matchingPixels
    }

    private fun isDonorPinPixel(pixel: Int): Boolean =
        Color.red(pixel) <= 25 &&
            Color.green(pixel) in 85..115 &&
            Color.blue(pixel) in 105..140

    /** Fake coarsened locations for emulator-only UI tests; never loaded or persisted to a backend. */
    private fun syntheticMapState() =
        PrivacyUiState(
            map = DonorMap(
                generatedAt = "2026-10-26T00:00:00Z",
                approximateOnly = true,
                areas = listOf(
                    DonorMapArea(
                        areaLabel = "Synthetic O+ area",
                        latitude = 14.5998,
                        longitude = 120.9846,
                        radiusMeters = 1200,
                        bloodType = "O+",
                        availability = "Available",
                        freshnessAt = "2026-10-26T00:00:00Z",
                        freshnessAgeMinutes = 4,
                        isStale = false,
                    ),
                    DonorMapArea(
                        areaLabel = "Synthetic A- area",
                        latitude = 14.6200,
                        longitude = 121.0100,
                        radiusMeters = 1600,
                        bloodType = "A-",
                        availability = "Unavailable",
                        freshnessAt = "2026-10-26T00:00:00Z",
                        freshnessAgeMinutes = 9,
                        isStale = false,
                    ),
                ),
            ),
        )

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
