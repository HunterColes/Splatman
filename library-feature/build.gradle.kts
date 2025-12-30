plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.junit)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
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
        jvmToolchain(17)

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
    implementation(project(":viewer-feature")) // For camera controller and Filament
    
    // Filament 3D rendering (from viewer-feature)
    implementation("com.google.android.filament:filament-android:1.68.2")
    implementation("com.google.android.filament:filament-utils-android:1.68.2")

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
    testImplementation(libs.test.robolectric)
    testImplementation(libs.test.androidx.core)
    androidTestImplementation(libs.bundles.common.android.test)
    debugImplementation(libs.debug.compose.manifest)

    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)
    kspAndroidTest(libs.hilt.compiler)
}
