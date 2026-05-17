# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Android-specific classes
-keep class com.carudibu.android.subuimirror.** { *; }
-keep class * extends android.app.Service
-keep class * extends android.app.Presentation

# Keep MediaProjection related classes
-keep class android.media.projection.** { *; }
-keep class android.hardware.display.** { *; }

# Keep Material components
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# Keep AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**
