package com.lifelink.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.feature.auth.AuthScreen
import com.lifelink.app.feature.auth.AuthState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun authScreenRendersOnDevice() {
        composeRule.activity.runOnUiThread {
            composeRule.activity.setContent {
                LifeLinkTheme {
                    AuthScreen(
                        state = AuthState.SignedOut,
                        onSignIn = { _, _ -> },
                        onSignUp = { _, _ -> },
                        onPasswordReset = { },
                        onResendConfirmation = { },
                        onUpdatePassword = { _, _ -> }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Welcome back. Sign in to continue.").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        composeRule.onNodeWithText("Email address").assertIsDisplayed()
    }
}
