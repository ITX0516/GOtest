# Keep JNI native bridge classes
-keep class com.weiqi.app.engine.jni.** { *; }
-keep class com.weiqi.app.engine.**$* { *; }

# Keep SGF data classes
-keep class com.weiqi.app.sgf.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
