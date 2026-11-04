# Keep the app entry points referenced from the manifest
-keep class dev.opencode.mobile.** { *; }

# WebView JS interface bridge
-keepclassmembers class dev.opencode.mobile.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Coroutines
-dontwarn kotlinx.coroutines.**
