plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    // Static analysis / formatting. Applied in :app so the tasks run per module.
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("com.diffplug.spotless") version "8.10.3" apply false
}
