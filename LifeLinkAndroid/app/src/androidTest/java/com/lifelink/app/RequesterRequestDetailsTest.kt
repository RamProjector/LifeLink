package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.navigation.LifeLinkShell
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.domain.ActiveRequestStatus
import com.lifelink.app.domain.RequestHistoryItem
import com.lifelink.app.feature.donor.BecomeDonorUiState
import com.lifelink.app.feature.donor.DonorUiState
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState
import com.lifelink.app.feature.privacy.PrivacyUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequesterRequestDetailsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun requesterCanOpenSavedRequestDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("lifelink_welcome", 0)
            .edit()
            .putBoolean("seen_requester-details-account", true)
            .commit()
        composeRule.activity.setContent {
            LifeLinkTheme {
                LifeLinkShell(
                    state = EmergencyRequestUiState(
                        requestHistory = listOf(
                            RequestHistoryItem(
                                requestId = "request-details-1",
                                status = ActiveRequestStatus.FULFILLED,
                                bloodType = "O+",
                                units = 2,
                                urgency = "Urgent",
                                facilityName = "Manila General Hospital",
                                area = "Quezon City",
                                notificationsCreated = 4,
                                matchesResponded = 2,
                            ),
                        ),
                    ),
                    onAction = {},
                    donorState = DonorUiState(),
                    onDonorAction = {},
                    becomeDonorState = BecomeDonorUiState(loading = false),
                    onBecomeDonorAction = {},
                    privacyState = PrivacyUiState(),
                    onPrivacyAction = {},
                    role = UserRole.REQUESTER,
                    onSwitchRole = {},
                    accountEmail = "requester@example.invalid",
                    accountUserId = "requester-details-account",
                    accountDisplayName = "Requester",
                    profileSaving = false,
                    profileMessage = null,
                    onSaveProfile = {},
                    themeMode = com.lifelink.app.core.ui.theme.ThemeMode.LIGHT,
                    onThemeModeChange = {},
                    onRequestPasswordReset = {},
                    onSignOut = {},
                    updates = emptyList(),
                    onUpdateRead = {},
                    onMarkAllUpdatesRead = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav-REQUESTS").performClick()
        composeRule.onNodeWithTag("request-history-request-details-1").performClick()
        composeRule.onNodeWithText("Request details").assertIsDisplayed()
        composeRule.onNodeWithText("O+").assertIsDisplayed()
        composeRule.onNodeWithText("Manila General Hospital").assertIsDisplayed()
        composeRule.onNodeWithText("Quezon City").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithTag("request-history-request-details-1").assertIsDisplayed()
    }
}
