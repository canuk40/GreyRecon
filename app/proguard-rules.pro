# ProGuard/R8 rules for GreyRecon

# Jetpack Compose
# Compose typically packages its own rules, but keep common attributes for safety.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# AndroidX Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlinx Serialization
-keepattributes *Annotation*,Keep
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keepclassmembers class * {
    *** serializer(...);
}

# Ktor Server & Model Context Protocol (MCP) SDK
# Ktor Server utilizes extensive reflection for feature installation, content negotiation,
# and routing setup. We keep the ktor and modelcontextprotocol classes to prevent R8 from stripping them.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.modelcontextprotocol.** { *; }
-dontwarn io.modelcontextprotocol.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Firebase (Crashlytics & Analytics)
# Firebase SDKs provide internal consumer ProGuard rules, but adding dontwarns helps with compilation.
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Google Play Billing Library
-dontwarn com.android.billingclient.**
