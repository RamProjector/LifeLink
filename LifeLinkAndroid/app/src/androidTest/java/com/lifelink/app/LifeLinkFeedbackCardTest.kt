package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifelink.app.core.ui.LifeLinkFeedbackCard
import com.lifelink.app.core.ui.LifeLinkFeedbackLevel
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifeLinkFeedbackCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun errorFeedbackShowsRecoveryActionAndInvokesIt() {
        var retryCount = 0
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme {
                    LifeLinkFeedbackCard(
                        title = "Couldn’t load donor areas",
                        message = "Check your connection and try again.",
                        level = LifeLinkFeedbackLevel.Error,
                        actionLabel = "Retry",
                        onAction = { retryCount += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Couldn’t load donor areas").assertIsDisplayed()
        composeRule.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }
    }

    @Test
    fun routineFeedbackCanRenderWithoutAnAction() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme {
                    LifeLinkFeedbackCard(
                        title = "Availability updated",
                        message = "Your preference was saved.",
                        level = LifeLinkFeedbackLevel.Success,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Availability updated").assertIsDisplayed()
        composeRule.onNodeWithText("Your preference was saved.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }
}
