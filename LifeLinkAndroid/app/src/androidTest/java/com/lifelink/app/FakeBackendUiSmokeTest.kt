package com.lifelink.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test the installable demo variant, including its local fake API and seeded session. */
@RunWith(AndroidJUnit4::class)
class FakeBackendUiSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fakeBackendBuildOpensRequesterWorkspaceWithoutRealSignIn() {
        assumeTrue("Only applies to the isolated uiTest APK", BuildConfig.LIFELINK_FAKE_BACKEND)

        composeRule.onNodeWithText("UI test build — fake backend").assertIsDisplayed()
        composeRule.onNodeWithText("Continue demo").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Create emergency request").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Create emergency request").assertIsDisplayed()
    }
}
