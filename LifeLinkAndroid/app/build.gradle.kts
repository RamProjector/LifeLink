plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("io.gitlab.arturbosch.detekt")
    id("com.diffplug.spotless")
}

android {
    namespace = "com.lifelink.app"
    compileSdk = 35
    val apiBaseUrl = providers.gradleProperty("lifelinkApiBaseUrl")
        .orElse(providers.environmentVariable("LIFELINK_API_BASE_URL"))
        .orElse("https://lifelink-api-uzje.onrender.com/")
        .get()
    val apiToken = providers.gradleProperty("lifelinkApiToken")
        .orElse(providers.environmentVariable("LIFELINK_API_TOKEN"))
        .orElse("development-user")
        .get()
    val supabaseUrl = providers.gradleProperty("supabaseUrl")
        .orElse(providers.environmentVariable("SUPABASE_URL"))
        .orElse("")
        .get()
    val supabasePublishableKey = providers.gradleProperty("supabasePublishableKey")
        .orElse(providers.environmentVariable("SUPABASE_PUBLISHABLE_KEY"))
        .orElse("")
        .get()

    defaultConfig {
        applicationId = "com.lifelink.app"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
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
                signingConfig = signingConfigs.create("stableDebug") {
                    storeFile = stableKeystore
                    storePassword = stableStorePassword
                    keyAlias = stableKeyAlias
                    keyPassword = stableKeyPassword
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.keepDebugSymbols += setOf(
            "**/libandroidx.graphics.path.so",
            "**/libdatastore_shared_counter.so",
            "**/libmaplibre.so"
        )
    }

    // Android Lint is a hard gate: a lint error fails the build. Warnings are
    // promoted to errors as well, but only once a baseline exists — the
    // pre-existing findings are recorded in `lint-baseline.xml` so only NEW
    // warnings fail. Generate/refresh the baseline with
    // `./gradlew :app:updateLintBaseline` and review the diff; it should only
    // ever shrink. Until the baseline is committed, errors still fail the build
    // but warnings do not, so enabling this cannot break CI on legacy code.
    val lintBaseline = file("lint-baseline.xml")
    lint {
        abortOnError = true
        warningsAsErrors = lintBaseline.exists()
        checkReleaseBuilds = true
        baseline = lintBaseline
        // Keep the report machine-readable for CI annotations.
        xmlReport = true
        htmlReport = true
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
            "src/androidTest/java"
        )
    )
}

// Spotless: formatting via ktlint. `ratchetFrom` limits the check to files that
// differ from the base branch, so the existing tree is not reformatted in one
// giant diff while every new or changed file must be clean.
spotless {
    ratchetFrom = "origin/main"
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.4.1").editorConfigOverride(
            mapOf(
                "max_line_length" to "140",
                // Compose @Composable functions are PascalCase by convention.
                "ktlint_standard_function-naming" to "disabled"
            )
        )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.4.1")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
