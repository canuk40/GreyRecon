plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    // Bumped 2.2.10 -> 2.2.21 (same minor line, patch-level) specifically so this project's compiler
    // can consume io.modelcontextprotocol:kotlin-sdk-server, which is itself compiled against Kotlin
    // 2.2.21 -- an older-than-that compiler can't read its module metadata. See GreyRecon.md.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
    // Crash reporting (see GreyRecon.md) -- versions confirmed live against Google's Maven repo,
    // not guessed, since a stale pin can fail to resolve or lag known Crashlytics fixes.
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.8" apply false
}
