plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

/**
 * Device QA for the lead's S22 runs (plan H). A self-instrumenting test APK
 * (com.frynetworks.fryqa): it instruments itself and drives the app with UI Automator by
 * resource-id / text only, so it keeps running while the app replaces itself (self-update hop).
 * Results go to <qa files dir>/results.jsonl.
 */
android {
    namespace = "com.frynetworks.fryqa"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test:rules:1.7.0")
    implementation(libs.androidx.test.uiautomator)
    // The target-package guard: a JVM module, so it has real unit tests (./gradlew :qa-guard:test).
    implementation(project(":qa-guard"))
}
