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
    namespace = "com.huntercoles.splatman.library"

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
    implementation(project(":splat-io"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.hilt)
    implementation(libs.kotlin.coroutines)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation)
    implementation(libs.navigation.hilt)
    implementation(libs.room)
    implementation(libs.room.ktx)
    implementation(libs.timber)
    testImplementation(libs.bundles.common.test)
    testImplementation(libs.test.androidx.core)
    testImplementation(libs.bundles.screenshot.test)
    testRuntimeOnly(libs.test.junit.vintage)
    androidTestImplementation(libs.bundles.common.android.test)
    debugImplementation(libs.debug.compose.manifest)

    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)
    kspAndroidTest(libs.hilt.compiler)
}

// Screenshot goldens (#67) under src/test/screenshots: testDebugUnitTest verifies them (roborazzi.test.verify in
// gradle.properties), recordRoborazziDebug re-records them on purpose (.forgejo/workflows/goldens.yml).
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
