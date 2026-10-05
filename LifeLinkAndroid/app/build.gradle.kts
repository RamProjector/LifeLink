plugins {
    id("com.android.application")
    // Kotlin support is built into AGP 9; do not apply kotlin.android here.
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("io.gitlab.arturbosch.detekt")
    id("com.diffplug.spotless")
}

// --- Release signing inputs -------------------------------------------------
// Resolved once, at the top level, so the signingConfig below and the
// `verifyReleaseSigning` task share ONE definition of "signing is configured".
// The production keystore is NEVER committed. Precedence: an explicit Gradle
// property first (e.g. -PlifelinkReleaseKeystore=... or a local
// gradle.properties), then the matching environment variable (used by CI
// secrets). Keeping the secret values out of the source tree is what makes a
// release build reproducible on a clean machine.
val releaseKeystorePath =
    providers
        .gradleProperty("lifelinkReleaseKeystore")
        .orElse(providers.environmentVariable("LIFELINK_RELEASE_KEYSTORE"))
        .orNull
val releaseStorePassword =
    providers
        .gradleProperty("lifelinkReleaseStorePassword")
        .orElse(providers.environmentVariable("LIFELINK_RELEASE_STORE_PASSWORD"))
        .orNull
val releaseKeyAlias =
    providers
        .gradleProperty("lifelinkReleaseKeyAlias")
        .orElse(providers.environmentVariable("LIFELINK_RELEASE_KEY_ALIAS"))
        .orNull
val releaseKeyPassword =
    providers
        .gradleProperty("lifelinkReleaseKeyPassword")
        .orElse(providers.environmentVariable("LIFELINK_RELEASE_KEY_PASSWORD"))
        .orNull
val releaseKeystoreFile = releaseKeystorePath?.takeIf { it.isNotBlank() }?.let { file(it) }

// Complete predicate: the keystore file must EXIST and every credential must be
// non-blank. A partial configuration (e.g. only the path set) is not signed.
val releaseSigningConfigured =
    releaseKeystoreFile != null &&
        releaseKeystoreFile.exists() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.lifelink.app"
    compileSdk = 37
    val apiBaseUrl =
        providers
            .gradleProperty("lifelinkApiBaseUrl")
            .orElse(providers.environmentVariable("LIFELINK_API_BASE_URL"))
            .orElse("https://lifelink-api-uzje.onrender.com/")
            .get()
    val apiToken =
        providers
            .gradleProperty("lifelinkApiToken")
            .orElse(providers.environmentVariable("LIFELINK_API_TOKEN"))
            .orElse("development-user")
            .get()
    val supabaseUrl =
        providers
            .gradleProperty("supabaseUrl")
            .orElse(providers.environmentVariable("SUPABASE_URL"))
            .orElse("")
            .get()
    val supabasePublishableKey =
        providers
            .gradleProperty("supabasePublishableKey")
            .orElse(providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY"))
            .orElse("")
            .get()

    // --- Release signing -----------------------------------------------------
    // The keystore location and credentials are resolved once at the top of this
    // file (see `releaseSigningConfigured`), so this signingConfig and the
    // `verifyReleaseSigning` task share a single definition of "configured".
    val releaseSigningConfig =
        if (releaseSigningConfigured) {
            val keystore = requireNotNull(releaseKeystoreFile)
            signingConfigs.create("release") {
                storeFile = keystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        } else {
            logger.lifecycle(
                "LifeLink: release signing is not configured, so the release APK will be UNSIGNED. " +
                    "Set lifelinkReleaseKeystore / lifelinkReleaseStorePassword / lifelinkReleaseKeyAlias / " +
                    "lifelinkReleaseKeyPassword (or the LIFELINK_RELEASE_* environment variables) to build " +
                    "a signed, distributable APK. See docs/ANDROID_RELEASE_SIGNING.md.",
            )
            null
        }

    defaultConfig {
        applicationId = "com.lifelink.app"
        minSdk = 24
        targetSdk = 37
        versionCode =
            providers
                .gradleProperty("versionCode")
                .orElse("1")
                .get()
                .toInt()
        versionName = providers.gradleProperty("versionName").orElse("1.0.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "LIFELINK_API_BASE_URL", "\"${apiBaseUrl.replace("\\\"", "\\\\\"")}\"")
        buildConfigField("String", "LIFELINK_API_TOKEN", "\"${apiToken.replace("\\\"", "\\\\\"")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.replace("\\\"", "\\\\\"")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${supabasePublishableKey.replace("\\\"", "\\\\\"")}\"")
    }

    buildTypes {
        debug {
            val stableKeystore = file("stable-debug.keystore")
            val stableStorePassword = providers.environmentVariable("LIFELINK_DEBUG_STORE_PASSWORD").orNull
            val stableKeyAlias = providers.environmentVariable("LIFELINK_DEBUG_KEY_ALIAS").orNull
            val stableKeyPassword = providers.environmentVariable("LIFELINK_DEBUG_KEY_PASSWORD").orNull
            if (stableKeystore.exists() && stableStorePassword != null && stableKeyAlias != null && stableKeyPassword != null) {
                signingConfig =
                    signingConfigs.create("stableDebug") {
                        storeFile = stableKeystore
                        storePassword = stableStorePassword
                        keyAlias = stableKeyAlias
                        keyPassword = stableKeyPassword
                    }
            }
        }
        release {
            // Sign with the configured production keystore when one is present;
            // otherwise the APK is left unsigned and a warning is printed above.
            signingConfig = releaseSigningConfig
            // R8 code shrinking/obfuscation plus resource shrinking. The rules in
            // proguard-rules.pro keep Retrofit/Gson/Room/Firebase/MapLibre working.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.keepDebugSymbols +=
            setOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so",
                "**/libmaplibre.so",
            )
    }

    // Android Lint is a hard gate: a lint error fails the build. Warnings are
    // promoted to errors only once `lint-baseline.xml` is committed, so turning
    // this on cannot break the build on the pre-existing tree. Generate the
    // baseline with `./gradlew :app:updateLintBaseline` and review the diff; it
    // should only ever shrink.
    //
    // The baseline must only be *configured* when the file exists: pointing
    // `baseline` at a missing path makes Lint write a new baseline and abort the
    // build ("Aborting build since new baseline file was created").
    val lintBaseline = file("lint-baseline.xml")
    lint {
        abortOnError = true
        warningsAsErrors = lintBaseline.exists()
        checkReleaseBuilds = true
        if (lintBaseline.exists()) {
            baseline = lintBaseline
        }
        // Keep the report machine-readable for CI annotations.
        xmlReport = true
        htmlReport = true
    }
}

// Kotlin 2.x: `kotlinOptions` is removed; set the JVM target via the
// compilerOptions DSL instead.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Detekt: Kotlin static analysis. `buildUponDefaultConfig` keeps every default
// rule active; project-specific relaxations live in config/detekt/detekt.yml and
// pre-existing findings are grandfathered through detekt-baseline.xml.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/detekt-baseline.xml")
    source.setFrom(
        files(
            "src/main/java",
            "src/test/java",
            "src/androidTest/java",
        ),
    )
}

// Spotless: formatting via ktlint. `ratchetFrom` limits the check to files that
// differ from the base branch, so the existing tree is not reformatted in one
// giant diff while every new or changed file must be clean.
//
// The ratchet reference must exist locally: a shallow CI checkout has no
// `origin/main` ref, and Spotless fails the build with "No such reference
// 'origin/main'". Fall back to the current HEAD (which makes the ratchet a
// no-op) when the ref is absent, so the gate never breaks on a shallow clone.
spotless {
    val ratchetRef = "origin/main"
    val hasRatchetRef =
        try {
            val probe =
                providers
                    .exec {
                        commandLine("git", "rev-parse", "--verify", "--quiet", ratchetRef)
                        isIgnoreExitValue = true
                    }
            probe.standardOutput.asText
                .get()
                .isNotBlank()
        } catch (e: Exception) {
            false
        }
    ratchetFrom = if (hasRatchetRef) ratchetRef else "HEAD"
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.4.1").editorConfigOverride(
            mapOf(
                "max_line_length" to "140",
                // Compose @Composable functions are PascalCase by convention.
                "ktlint_standard_function-naming" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.4.1")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// --- Explicit build actions -------------------------------------------------
// Convenience tasks so a debug or a release build can be triggered by name from
// the command line, CI, or an IDE run configuration.
//
//   ./gradlew buildDebug    -> app/build/outputs/apk/debug/app-debug.apk
//   ./gradlew buildRelease  -> app/build/outputs/apk/release/app-release.apk
//
// `buildRelease` fails fast with an actionable message when no signing keystore
// is configured, instead of silently emitting an APK that cannot be published.
tasks.register("buildDebug") {
    group = "build"
    description = "Assembles the debug APK (app-debug.apk)."
    dependsOn("assembleDebug")
}

val verifyReleaseSigning =
    tasks.register("verifyReleaseSigning") {
        group = "verification"
        description = "Fails fast when release signing is not configured."
        doLast {
            // Same predicate the signingConfig uses: keystore file present AND
            // every credential non-blank. A partial configuration is not signed,
            // so buildRelease cannot succeed with an unsigned APK.
            if (!releaseSigningConfigured) {
                throw GradleException(
                    "Release signing is not configured. Set lifelinkReleaseKeystore / " +
                        "lifelinkReleaseStorePassword / lifelinkReleaseKeyAlias / lifelinkReleaseKeyPassword " +
                        "(or the LIFELINK_RELEASE_* environment variables). See docs/ANDROID_RELEASE_SIGNING.md.",
                )
            }
        }
    }

tasks.register("buildRelease") {
    group = "build"
    description = "Assembles the release APK; requires release signing to be configured."
    dependsOn(verifyReleaseSigning, "assembleRelease")
}

// Make sure the signing guard runs before the release APK is assembled.
tasks.matching { it.name == "assembleRelease" }.configureEach { mustRunAfter(verifyReleaseSigning) }
