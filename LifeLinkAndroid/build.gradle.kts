plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 has built-in Kotlin support: the org.jetbrains.kotlin.android
    // plugin must NOT be applied. AGP 9.4.1 bundles Kotlin 2.2.10, so the
    // Compose compiler plugin is pinned to the same version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    // Static analysis / formatting. Applied in :app so the tasks run per module.
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("com.diffplug.spotless") version "8.10.3" apply false
}
