# Keep the app entry points referenced from the manifest
-keep class dev.opencode.mobile.** { *; }

# WebView JS interface bridge
-keepclassmembers class dev.opencode.mobile.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Coroutines
-dontwarn kotlinx.coroutines.**

# androidx.security:security-crypto pulls in Google Tink, which references
# compile-time-only annotations that never land on the Android runtime classpath.
# R8 treats the dangling references as missing classes and fails the build.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**

# QR pairing scanner: zxing readers are resolved reflectively
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
