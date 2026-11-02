// Pure Kotlin/JVM: maths and model types, no Android imports (ADR 0001).
plugins {
    // By id: the Kotlin Gradle plugin is already on the root classpath, so a versioned alias would clash.
    id(libs.plugins.kotlin.jvm.get().pluginId)
    // Mutation testing (#66): `./gradlew :splat-core:pitest`, not in the gate; run by .forgejo/workflows/mutation.yml.
    alias(libs.plugins.pitest)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.test.junit)
    // pitest-junit5-plugin 1.2.x expects the launcher on the test runtime classpath, at the Jupiter's platform version.
    testRuntimeOnly(libs.test.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// tools/check.sh runs testDebugUnitTest; a JVM module only has `test` (ADR 0001).
tasks.register("testDebugUnitTest") {
    dependsOn("test")
}

// Mutants of every splat-core class, killed (or not) by the JUnit 5 tests. Reports: build/reports/pitest/
// (index.html, mutations.xml); pitest prints `>> Generated N mutations Killed M (S%)` to the log. Every artifact is
// resolved from the Gradle plugin portal or Maven Central with the build's other dependencies; the run needs no network.
pitest {
    pitestVersion.set(libs.versions.test.pitest.asProvider())
    junit5PluginVersion.set(libs.versions.test.pitest.junit5)
    targetClasses.set(listOf("com.huntercoles.splatman.*"))
    targetTests.set(listOf("com.huntercoles.splatman.*"))
    threads.set(2)
    jvmArgs.set(listOf("-Xmx1g"))
    timestampedReports.set(false)
    outputFormats.set(listOf("HTML", "XML"))
    verbose.set(false)
}
