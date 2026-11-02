// GLES 3.1 compute on an offscreen EGL context that one thread owns (ADR 0010). The CPU trainer in splat-train stays
// the reference and the fallback; the manifest does not require GLES 3.1 (devices without it train on the CPU).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.junit)
    alias(libs.plugins.kotlin)
}

android {
    compileSdk = 34
    namespace = "com.huntercoles.splatman.splat.gpu"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":splat-train"))

    testImplementation(libs.test.junit)
    androidTestImplementation(libs.test.android.junit)
    androidTestImplementation(libs.test.android.runner)
}
