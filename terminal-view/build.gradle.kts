// Vendored from canuk40/termux-kotlin-app -- see terminal-emulator/build.gradle.kts for the
// license/sourcing note (same Apache-2.0 exception applies to this module).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    api(project(":terminal-emulator"))
}
