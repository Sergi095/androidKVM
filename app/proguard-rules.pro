# The local NanoKVM terminal calls these annotated methods from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
