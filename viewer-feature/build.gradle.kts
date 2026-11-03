plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.junit)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.roborazzi)
}

android {
    compileSdk = 34
    namespace = "com.huntercoles.splatman.viewer"

    with (defaultConfig) {
        minSdk = 26
        targetSdk = 34
    }

    defaultConfig {
        testInstrumentationRunner = "com.huntercoles.splatman.core.utils.HiltTestRunner"
    }

    testOptions {
        // Robolectric (the screenshot goldens, #67) needs the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {

        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        }
    }

    sourceSets {
        getByName("androidTest") {
            java.srcDir(project(":core").file("src/androidTest/java"))
        }
        getByName("test") {
            java.srcDir(project(":core").file("src/test/java"))
        }
    }
}

dependencies {
    implementation(project(":core"))
    // The controller's public API returns splat-core math types (ADR 0006).
    api(project(":splat-core"))
    // The viewer opens PLY and .splat files (ADR 0007).
    implementation(project(":splat-io"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.hilt)
    implementation(libs.kotlin.coroutines)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation)
    implementation(libs.navigation.hilt)
    implementation(libs.timber)
    
    // CameraX for camera preview
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    
    testImplementation(libs.bundles.common.test)
    // Frame suite 4 (#146): the viewer's camera is rendered through the trainer's CPU rasteriser.
    testImplementation(project(":splat-train"))
    testImplementation(libs.bundles.screenshot.test)
    testRuntimeOnly(libs.test.junit.vintage)
    androidTestImplementation(libs.bundles.common.android.test)
    debugImplementation(libs.debug.compose.manifest)

    ksp(libs.hilt.compiler)
    kspAndroidTest(libs.hilt.compiler)
}

// Screenshot goldens (#67) under src/test/screenshots: testDebugUnitTest verifies them (roborazzi.test.verify in
// gradle.properties), recordRoborazziDebug re-records them on purpose (.forgejo/workflows/goldens.yml).
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
