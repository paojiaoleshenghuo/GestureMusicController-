# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep project models
-keep class com.example.gesturemusic.gesture.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.example.gesturemusic.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-keepattributes *Annotation*
