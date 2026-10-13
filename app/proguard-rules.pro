# ProGuard / R8 rules for Haze

# Keep native JNI bridge methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Haze Native & Engine Models and Controllers
-keep class com.example.haze.native_engine.** { *; }
-keep class com.example.haze.engine.** { *; }
-keep class com.example.haze.** { *; }

# Keep Chromium Content Shell and JNI Zero references
-keep class org.chromium.** { *; }
-keep interface org.chromium.** { *; }
-keep class org.jni_zero.** { *; }
-keep class gen.org.chromium.** { *; }
-keep class gen.base_module.** { *; }
-keep class J.** { *; }
-dontwarn org.chromium.**
-dontwarn org.jni_zero.**
-dontwarn gen.org.chromium.**
-dontwarn gen.base_module.**

# Keep Google Play Services Auth
-keep class com.google.android.gms.auth.api.signin.** { *; }
-dontwarn com.google.android.gms.**

# Keep Guava for Chromium Media Audio
-keep class com.google.common.collect.ImmutableMap { *; }
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**

# Keep AndroidX classes referenced by Chromium JNI signatures (e.g. AccessibilityNodeInfoCompat)
-keep class androidx.core.view.accessibility.** { *; }
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

