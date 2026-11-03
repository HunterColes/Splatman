plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.hilt)
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
    namespace = "com.huntercoles.splatman.capture"

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        targetSdk = 34
        // Robolectric (the screenshot goldens, #67) needs the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

    // ADR 0004: `full` tracks with ARCore (src/full); `foss` (F-Droid) has no proprietary library and no tracker.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
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
    }
}

// The gate (tools/check.sh) runs testDebugUnitTest and lintDebug; with flavours AGP names them per flavour only.
tasks.register("testDebugUnitTest") { dependsOn("testFullDebugUnitTest", "testFossDebugUnitTest") }
tasks.register("lintDebug") { dependsOn("lintFullDebug", "lintFossDebug") }
// The same for the screenshot goldens (#67), whose tasks are per flavour too: both flavours draw the same overlay, so
// recording one writes the goldens both verify.
tasks.register("recordRoborazziDebug") { dependsOn("recordRoborazziFossDebug") }
tasks.register("compareRoborazziDebug") { dependsOn("compareRoborazziFossDebug") }
tasks.register("verifyRoborazziDebug") { dependsOn("verifyRoborazziFullDebug", "verifyRoborazziFossDebug") }

dependencies {
    implementation(project(":core"))
    implementation(project(":viewer-feature"))
    // TrackedFrame carries splat-core model types (ADR 0002).
    api(project(":splat-core"))
    // The processing runner reads the capture bundle, writes splat.ply and runs the Trainer (ADR 0005).
    implementation(project(":splat-io"))
    implementation(project(":splat-train"))
    // ARCore behind the PoseSource seam, optional at install (ADR 0004); only src/full's tracking/arcore/ imports it.
    "fullImplementation"(libs.arcore)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.hilt)
    implementation(libs.kotlin.coroutines)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation)
    implementation(libs.navigation.hilt)
    implementation(libs.room)
    implementation(libs.timber)
    
    // Testing dependencies
    testImplementation(libs.bundles.common.test)
    testImplementation(libs.test.androidx.core)
    testImplementation(libs.bundles.screenshot.test)
    testRuntimeOnly(libs.test.junit.vintage)
    androidTestImplementation(libs.bundles.common.android.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.test.android.junit)
    debugImplementation(libs.debug.compose.manifest)

    ksp(libs.hilt.compiler)
}

// Screenshot goldens (#67) under src/test/screenshots: testDebugUnitTest verifies them (roborazzi.test.verify in
// gradle.properties), recordRoborazziDebug re-records them on purpose (.forgejo/workflows/goldens.yml).
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
