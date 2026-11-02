// Pure Kotlin/JVM: the trainer and CPU rasteriser, no Android imports (ADR 0001).
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
    api(project(":splat-core"))
    // Trainer.train returns a Flow (ADR 0005): the JVM core artifact, same version as the app's coroutines.
    api(libs.kotlin.coroutines.core)
    testImplementation(libs.test.junit)
    // The frame suite (#143) loads its fixtures through the app's PLY reader.
    testImplementation(project(":splat-io"))
}

tasks.test {
    useJUnitPlatform()
}

// tools/check.sh runs testDebugUnitTest; a JVM module only has `test` (ADR 0001).
tasks.register("testDebugUnitTest") {
    dependsOn("test")
}
