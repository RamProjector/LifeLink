package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifelink.app.core.ui.LifeLinkFeedbackLevel
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import com.lifelink.app.feature.donor.GpsLocationCard
import com.lifelink.app.feature.donor.GpsLocationCardState
import com.lifelink.app.feature.donor.GpsSettingsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DonorGpsFeedbackCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun permission_denial_announces_error_and_offers_app_settings() {
        var appSettingsOpened = 0
        render(
            state = GpsLocationCardState(
                message = "Precise location permission is required to register as a donor. Enable it in app settings to continue.",
                messageLevel = LifeLinkFeedbackLevel.Error,
                settingsAction = GpsSettingsAction.APP_PERMISSION,
            ),
            onOpenSettings = { appSettingsOpened++ },
        )

        composeRule.onNodeWithTag("donor-gps-message")
            .assertIsDisplayed()
            .assertTextEquals(
                "Precise location permission is required to register as a donor. Enable it in app settings to continue.",
            )
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        composeRule.onNodeWithTag("donor-gps-settings").performClick()

        assertEquals(1, appSettingsOpened)
        assertTrue(composeRule.onAllNodesWithTag("donor-gps-location-settings").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun disabled_device_location_offers_a_distinct_settings_action() {
        var locationSettingsOpened = 0
        render(
            state = GpsLocationCardState(
                message = "Device location services are unavailable. Open location settings and try again.",
                messageLevel = LifeLinkFeedbackLevel.Warning,
                settingsAction = GpsSettingsAction.DEVICE_LOCATION,
            ),
            onOpenSettings = { locationSettingsOpened++ },
        )

        composeRule.onNodeWithTag("donor-gps-message")
            .assertIsDisplayed()
            .assertTextEquals("Device location services are unavailable. Open location settings and try again.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithTag("donor-gps-location-settings").performClick()

        assertEquals(1, locationSettingsOpened)
        assertTrue(composeRule.onAllNodesWithTag("donor-gps-settings").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun missing_recent_fix_has_retry_without_misleading_settings_action() {
        var retryCount = 0
        render(
            state = GpsLocationCardState(
                message = "No recent GPS fix was available. Move to an open area and try again.",
                messageLevel = LifeLinkFeedbackLevel.Error,
            ),
            onRequestCurrentGps = { retryCount++ },
        )

        composeRule.onNodeWithTag("donor-gps-message")
            .assertIsDisplayed()
            .assertTextEquals("No recent GPS fix was available. Move to an open area and try again.")
        composeRule.onNodeWithTag("capture-donor-gps").performClick()

        assertEquals(1, retryCount)
        assertTrue(composeRule.onAllNodesWithTag("donor-gps-location-settings").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("donor-gps-settings").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun successful_capture_feedback_is_polite_and_includes_accuracy() {
        render(
            state = GpsLocationCardState(
                capturedThisSession = true,
                message = "Current GPS captured (about ±25 m accuracy).",
                messageLevel = LifeLinkFeedbackLevel.Success,
            ),
        )

        composeRule.onNodeWithTag("donor-gps-message")
            .assertIsDisplayed()
            .assertTextEquals("Current GPS captured (about ±25 m accuracy).")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    private fun render(
        state: GpsLocationCardState,
        onRequestCurrentGps: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme(themeMode = ThemeMode.LIGHT) {
                    GpsLocationCard(
                        state = state,
                        onRequestCurrentGps = onRequestCurrentGps,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
