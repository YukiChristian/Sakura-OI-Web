# R8 rules for the published APK (see app/build.gradle.kts).

# Open WebUI's page calls these Kotlin methods by name through WebView.addJavascriptInterface.
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Vosk (offline speech recognition for "Oye, Sakura") reaches Java through JNA, which finds
# classes, fields and callbacks by name from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Crash reports keep useful line numbers.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
