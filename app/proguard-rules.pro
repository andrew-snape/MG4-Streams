# MG4 Streams release build (R8).
# Media3, AndroidX and Guava ship their own keep rules. The app uses no reflection; the activity
# and the playback service are kept because the manifest names them.

# Readable stack traces in logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Guava references optional annotation classes that aren't on Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn afu.org.checkerframework.**
