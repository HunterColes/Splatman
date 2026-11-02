// Pure Kotlin/JVM: maths and model types, no Android imports (ADR 0001).
plugins {
    // By id: the Kotlin Gradle plugin is already on the root classpath, so a versioned alias would clash.
    id(libs.plugins.kotlin.jvm.get().pluginId)
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
}

tasks.test {
    useJUnitPlatform()
}

// tools/check.sh runs testDebugUnitTest; a JVM module only has `test` (ADR 0001).
tasks.register("testDebugUnitTest") {
    dependsOn("test")
}
