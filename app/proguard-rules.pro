# Keep the app entry points referenced from the manifest
-keep class dev.opencode.mobile.** { *; }

# WebView JS interface bridge
-keepclassmembers class dev.opencode.mobile.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Coroutines
-dontwarn kotlinx.coroutines.**

# QR pairing scanner: zxing readers are resolved reflectively
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
