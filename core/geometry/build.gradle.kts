plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.androidcad.geometry"
    compileSdk = 36
    // NDK r28+ erzeugt standardmäßig 16-KB-ausgerichtete Bibliotheken (Play-Pflicht ab Android 15).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Googlebooks laufen auch auf Intel/AMD → x86_64 neben arm64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // Referenzmodell + von OpenSCAD erzeugte STLs als Ground Truth für Instrumented Tests.
        getByName("androidTest").assets.srcDir(rootProject.file("samples"))
    }
}

dependencies {
    api(project(":core:lang"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test)
}
