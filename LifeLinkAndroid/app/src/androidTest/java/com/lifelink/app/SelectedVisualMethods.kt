package com.lifelink.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.RunNotifier

/**
 * Limits the emulator evidence run to explicitly named regression journeys.
 * It delegates to AndroidJUnit4 so Compose/Activity instrumentation hooks remain active.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SelectedTestMethods(vararg val value: String)

class SelectedMethodsRunner(testClass: Class<*>) : Runner() {
    private val selectedMethodNames = testClass.getAnnotation(SelectedTestMethods::class.java)
        ?.value
        ?.toSet()
        .orEmpty()

    private val delegate = AndroidJUnit4(testClass).apply {
        check(selectedMethodNames.isNotEmpty()) { "SelectedTestMethods must name at least one test." }
        filter(
            object : Filter() {
                override fun shouldRun(description: Description): Boolean =
                    if (description.isTest) {
                        description.methodName in selectedMethodNames
                    } else {
                        description.children.any(::shouldRun)
                    }

                override fun describe(): String =
                    "Selected instrumentation methods: ${selectedMethodNames.joinToString()}"
            },
        )
    }

    override fun getDescription(): Description = delegate.description

    override fun run(notifier: RunNotifier) = delegate.run(notifier)
}

@RunWith(SelectedMethodsRunner::class)
@SelectedTestMethods(
    "navigatesCoreWorkflowsAndCapturesEvidence",
    "donorMapErrorKeepsMapVisibleAndRetryable",
)
class LifeLinkVisualSmokeTest : LifeLinkWorkflowVisualTest()

@RunWith(SelectedMethodsRunner::class)
@SelectedTestMethods(
    "donor_can_open_chat_and_system_back_returns_to_the_donor_workspace",
    "unsaved_profile_radius_and_gps_controls_survive_full_screen_transitions",
)
class ConversationNavigationSmokeTest : ConversationNavigationTest()
