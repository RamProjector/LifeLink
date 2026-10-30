package com.lifelink.app

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * One instrumentation entry point for the complete visual-review matrix.
 *
 * AndroidJUnitRunner's single-class filter must not be given a comma-delimited
 * mixture of class and method selectors: it can silently execute only the
 * first class. The suite makes every included class explicit and reportable.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    AuthScreenVisualTest::class,
    LifeLinkWorkflowVisualTest::class,
)
class LifeLinkVisualReviewSuite
