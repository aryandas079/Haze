# ProGuard / R8 rules for Haze Browser
# Optimized for high DEX code shrinking, obfuscation (>70%), and optimization

# -------------------------------------------------------------
# 1. Native JNI Bridge Entry Points
# -------------------------------------------------------------
# Preserve native method declarations across all classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve NativeDecision data class used via JNI FindClass/GetMethodID in jni_bridge.cc
-keep class com.example.haze.native_engine.NativeDecision {
    <init>(...);
    *;
}

# Preserve HazeNativeBridge class and methods loaded via System.loadLibrary("hazeshield")
-keep class com.example.haze.native_engine.HazeNativeBridge {
    <methods>;
}

# -------------------------------------------------------------
# 2. JavaScript Interface Bridges
# -------------------------------------------------------------
# Preserve JavascriptInterface annotated methods across Haze & WebViews
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# -------------------------------------------------------------
# 3. Chromium JNI & Native Bridge Annotations
# -------------------------------------------------------------
-keep @org.jni_zero.CalledByNative class * { *; }
-keepclassmembers class * {
    @org.jni_zero.CalledByNative <methods>;
    @org.jni_zero.CalledByNativeUnchecked <methods>;
    @org.jni_zero.AccessedByNative <fields>;
}

-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
-dontwarn org.chromium.**
-dontwarn gen.org.chromium.**
-dontwarn gen.base_module.**
-dontwarn J.**

# -------------------------------------------------------------
# 4. Google Play Services & Auth
# -------------------------------------------------------------
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
-dontwarn com.google.mlkit.**

# -------------------------------------------------------------
# 5. Guava, AndroidX, and Third-party Libraries
# -------------------------------------------------------------
# Allow R8 to shrink, strip, inline, and obfuscate Guava and AndroidX freely
# Only keep the specific ImmutableMap class referenced by media audio reflection
-keep class com.google.common.collect.ImmutableMap {
    public static com.google.common.collect.ImmutableMap of(...);
    public static com.google.common.collect.ImmutableMap copyOf(...);
}
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn androidx.**


