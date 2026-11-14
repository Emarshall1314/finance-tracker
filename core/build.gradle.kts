import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Bytecode targets Java 17 so the module runs on Android (minSdk 26). Stick to Kotlin stdlib and
// java.time / java.math APIs that exist on API 26: no Java 9+ only JDK methods.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // core has no runtime dependencies beyond the Kotlin stdlib.
    testImplementation(libs.junit)
}

// So `./gradlew testDebugUnitTest` at the root also runs the core tests.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs core unit tests (alias used by CI alongside the app's testDebugUnitTest)."
    dependsOn("test")
}
