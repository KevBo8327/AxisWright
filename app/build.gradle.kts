plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.androidcad.app"
    compileSdk = 36

    defaultConfig {
        // endgültige App-Kennung im Play Store (nach dem ersten Upload nicht mehr änderbar); der Code-Namespace bleibt dev.androidcad
        applicationId = "io.github.kevbo8327.partwright"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Googlebooks laufen auch auf Intel/AMD → x86_64 ist Pflicht neben arm64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:lang"))
    implementation(project(":core:geometry"))
    implementation(project(":core:project"))
    implementation(project(":core:model"))
    implementation(project(":core:viewport"))
    implementation(project(":core:sketchedit"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
