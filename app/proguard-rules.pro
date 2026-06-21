# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Strip Log.v, Log.d, Log.i in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep attributes for Gson and Coroutines
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# Keep Gson data models
-keep class com.cfd2474.eudremoteassist.network.** { *; }

# Keep WebRTC classes to prevent JNI crashes in release builds
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Workaround for R8 synthetic lambda merge bug in AGP causing NPEs in CameraX/MLKit
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-keepclassmembers class androidx.camera.** { *; }
-keepclassmembers class com.google.mlkit.** { *; }
