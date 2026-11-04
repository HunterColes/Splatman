// Native SfM front end (epic #200, ADR 0012): OpenCV only, behind a handle-based JNI bridge (NativeSfm). Everything
// that is not OpenCV (types, priors, bundle adjustment, alignment) stays pure Kotlin in splat-core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin)
}

android {
    compileSdk = 34
    namespace = "com.huntercoles.splatman.splat.sfm.nativebridge"
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // OpenCV's prefab package is built against the shared C++ runtime.
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    packaging {
        jniLibs {
            // The NDK's C++ runtime comes from our CMake build and again inside the OpenCV AAR (same NDK 27). An app
            // that depends on this module needs the same line.
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }

    sourceSets {
        // The frame suite's cactus fixture (#143), for the synthetic scan the reconstruction test renders (#226).
        getByName("androidTest").assets.srcDir("../splat-train/src/test/resources/frames")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.opencv)
    // NativeSfm.reconstruct returns splat-core's SparseModel (#226).
    api(project(":splat-core"))

    androidTestImplementation(libs.test.android.junit)
    androidTestImplementation(libs.test.android.runner)
    // The reconstruction test renders the synthetic scan (#224) of the cactus fixture, read by the app's PLY reader.
    androidTestImplementation(testFixtures(project(":splat-train")))
    androidTestImplementation(project(":splat-io"))
}
