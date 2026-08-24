import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// Release signing -- credentials live outside the source tree in keystore/keystore.properties
// (not committed anywhere), same pattern as ObsidianBox Modern's build.gradle.kts.
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.greyrecon.app"
    compileSdk = 37   // compose-bom 2026.08.00 requires API 37+ to compile against

    defaultConfig {
        applicationId = "com.greyrecon.app"
        minSdk = 26   // ConnectivityManager.NetworkCallback + modern WiFi APIs need this floor
        // Raised to 36 for real Play Store submission (new-app floor as of Aug 2026 -- see
        // GreyRecon.md). Was deliberately held at 30 for the legacy `untrusted_app_30` SELinux
        // domain, which let ArpTableDiscoveryService's native RTM_GETNEIGH call work (the current
        // `untrusted_app` domain returns EPERM on that same sendmsg -- confirmed live). That native
        // path degrades to its existing `ip neigh show` shell-out and TCP-probe fallbacks at this
        // targetSdk rather than losing discovery outright -- see GreyRecon.md for the full tradeoff.
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file("keystore/" + keystoreProperties["storeFile"])
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // AGP's modern default keeps native libs compressed inside the APK, mmap'd directly at
            // runtime -- fine for libs only ever loaded via System.loadLibrary() from this app's own
            // process, but greyrecon_exec.so needs to be a real file on disk: it's handed to a CHILD
            // process via LD_PRELOAD, whose own dynamic linker opens it by literal path rather than
            // going through Android's APK-aware ClassLoader loading path. Confirmed live: with the
            // default packaging, applicationInfo.nativeLibraryDir existed but was empty on disk.
            useLegacyPackaging = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.android.billingclient:billing-ktx:8.3.0")

    // Crash reporting (see GreyRecon.md) -- BoM pins every Firebase artifact's version together,
    // no per-artifact version string needed on the two lines below.
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics") // Crashlytics' own recommended pairing -- powers Crash-free users/session-count context in the console

    // Official Kotlin MCP SDK (Apache-2.0/MIT, modelcontextprotocol/kotlin-sdk) replacing the
    // hand-rolled NanoHTTPD Streamable HTTP server -- real spec compliance (session ids, SSE stream,
    // protocol version negotiation) instead of a hardcoded protocolVersion with no update path.
    // Version pinned to 0.10.0 specifically because it's the newest release still compiled against
    // Kotlin 2.2.x (2.2.21) -- 0.12.0+ requires Kotlin 2.3.21, which this project isn't on.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.10.0")
    implementation(platform("io.ktor:ktor-bom:3.2.3"))
    implementation("io.ktor:ktor-server-cio") // CIO, not Netty (the SDK's own sample default) -- pure-Kotlin/coroutines engine, no native transport, the better fit for Android.
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-sse")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Terminal engine + PTY allocation for the in-app terminal (nmap and other user-supplied CLI
    // tools run through it -- GreyRecon never bundles nmap itself, see GreyRecon.md). Vendored as
    // local Gradle modules (see :terminal-emulator/:terminal-view) rather than pulled via JitPack --
    // JitPack's build history for termux-app is genuinely unreliable for its native (ndk-build)
    // modules on many tags. Apache-2.0, carved out of termux-app's GPLv3 by its own LICENSE.md.
    implementation(project(":terminal-view"))
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
