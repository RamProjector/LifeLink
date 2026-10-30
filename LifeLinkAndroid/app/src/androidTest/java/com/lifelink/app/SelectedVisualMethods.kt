package com.lifelink.app

import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod

/**
 * Limits the emulator evidence run to explicitly named regression journeys.
 * The full test classes remain available to normal instrumentation runs.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SelectedTestMethods(vararg val value: String)

class SelectedMethodsRunner(testClass: Class<*>) : BlockJUnit4ClassRunner(testClass) {
    private val selectedMethodNames = testClass.getAnnotation(SelectedTestMethods::class.java)
        ?.value
        ?.toSet()
        .orEmpty()

    override fun computeTestMethods(): List<FrameworkMethod> {
        check(selectedMethodNames.isNotEmpty()) { "SelectedTestMethods must name at least one test." }
        val discovered = super.computeTestMethods()
        val selected = discovered.filter { it.name in selectedMethodNames }
        val missing = selectedMethodNames - selected.map { it.name }.toSet()
        check(missing.isEmpty()) { "Selected instrumentation tests were not found: $missing" }
        return selected
    }
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
