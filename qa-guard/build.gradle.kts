import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * The pure part of the :qa on-device suite (the target-package guard). A com.android.test module
 * has no unit-test variant, so the guard lives here as a plain JVM library with its own JUnit
 * tests: ./gradlew :qa-guard:test
 */
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
