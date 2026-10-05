# 1. Disable Obfuscation (keep names human-readable)
-dontobfuscate

# 2. Preserve JavaScript Interface members
# R8 needs this because it can't "see" calls coming from JS
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 3. Specifically keep the WebView bridge in case it's missed
-keep class r3.graffiti.WebViewActivity$AndroidBridge {
    public *;
}

# Optional: Keep line numbers for better stack traces
-keepattributes SourceFile,LineNumberTable
