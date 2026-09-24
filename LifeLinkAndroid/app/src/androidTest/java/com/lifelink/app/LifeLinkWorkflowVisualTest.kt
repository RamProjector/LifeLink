package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.navigation.LifeLinkShell
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState
import com.lifelink.app.feature.donor.DonorUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LifeLinkWorkflowVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun navigatesCoreWorkflowsAndCapturesEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("lifelink_welcome", 0)
            .edit()
            .putBoolean("seen_visual-account", true)
            .commit()

        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme {
                    LifeLinkShell(
                        state = EmergencyRequestUiState(),
                        onAction = {},
                        donorState = DonorUiState(),
                        onDonorAction = {},
                        role = UserRole.REQUESTER,
                        accountEmail = "visual-test@example.invalid",
                        accountUserId = "visual-account",
                        accountDisplayName = "Visual Test",
                        profileSaving = false,
                        profileMessage = null,
                        onSaveProfile = {},
                        themeMode = com.lifelink.app.core.ui.theme.ThemeMode.SYSTEM,
                        onThemeModeChange = {},
                        onRequestPasswordReset = {},
                        onSignOut = {},
                        updates = emptyList(),
                        onUpdateRead = {},
                        onMarkAllUpdatesRead = {}
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertVisible("Find help when it matters")
        capture("workflow-home")

        tapTab("Requests")
        assertVisible("No active request")
        capture("workflow-requests")

        tapTab("Updates")
        assertVisible("Nothing new")
        capture("workflow-updates")

        tapTab("Profile")
        assertVisible("Requester profile")
        capture("workflow-settings-profile")

        tap("Legal")
        assertVisible("Legal and conduct")
        capture("workflow-settings-legal")

        tap("Theme")
        assertVisible("Appearance")
        capture("workflow-settings-theme")

        tap("Security")
        assertVisible("Reset password")
        capture("workflow-settings-security")

        tap("Profile")
        tap("Become a donor")
        assertVisible("Donor workspace")
        assertVisible("Finish donor setup")
        capture("workflow-donor")
    }

    private fun tapTab(label: String) {
        composeRule.onAllNodesWithText(label, useUnmergedTree = true)[0].performClick()
        composeRule.waitForIdle()
    }

    private fun tap(label: String) {
        composeRule.onNodeWithText(label, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
    }

    private fun assertVisible(label: String) {
        composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val file = File("/sdcard/lifelink-$name.png")
        check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)) {
            "Could not capture $name visual evidence screenshot"
        }
    }
}
