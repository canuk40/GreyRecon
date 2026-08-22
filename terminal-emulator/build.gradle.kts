// Vendored from canuk40/termux-kotlin-app (a Kotlin conversion of termux/termux-app), which carries
// forward termux-app's own explicit LICENSE.md exception: this module (unlike the rest of the
// termux-app GPLv3 umbrella project) is Apache-2.0. See LICENSE-APACHE-2.0.txt in this directory and
// GreyRecon.md for the sourcing/license verification this was based on -- confirmed directly from
// the fork's own LICENSE.md, not assumed. Adapted here to GreyRecon's own Gradle/Kotlin toolchain
// versions rather than depending on termux-app's multi-module `project.properties.*` values.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
}
