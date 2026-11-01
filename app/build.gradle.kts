import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Signatur des Release-Builds: Der Upload-Schlüssel liegt NICHT im Repository. `keystore.properties` im Projektordner
 * (ebenfalls nicht eingecheckt) nennt ihn:
 *   storeFile=C:/Pfad/zu/paramworker-upload.jks
 *   storePassword=…
 *   keyAlias=upload
 *   keyPassword=…
 * Fehlt die Datei, bricht ein Release-Build ab – außer mit -PallowDebugSignedRelease=true (CI, lokale Tests): dann Debug-Schlüssel
 * (Google Play lehnt solche Pakete ab).
 */
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

val releaseTasks = setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle")
gradle.taskGraph.whenReady {
    if (keystoreProps.getProperty("storeFile") == null && findProperty("allowDebugSignedRelease") != "true" &&
        allTasks.any { it.project == project && it.name in releaseTasks }
    ) throw GradleException("Kein Upload-Schlüssel: keystore.properties anlegen, siehe README (nur zum Testen: -PallowDebugSignedRelease=true).")
}

android {
    namespace = "dev.androidcad.app"
    compileSdk = 36

    defaultConfig {
        // endgültige App-Kennung im Play Store (nach dem ersten Upload nicht mehr änderbar); der Code-Namespace bleibt dev.androidcad
        applicationId = "io.github.kevbo8327.paramworker"
        minSdk = 26
        targetSdk = 36
        // versionCode = Major·1000 + Minor·100 + Patch (0.9.0 → 900, 1.0.0 → 1000); jeder Upload in Play braucht einen höheren Wert
        versionCode = 902
        versionName = "0.9.2-beta"
        // Googlebooks laufen auch auf Intel/AMD → x86_64 ist Pflicht neben arm64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) create("upload") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.haze)
    implementation(libs.billing.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
