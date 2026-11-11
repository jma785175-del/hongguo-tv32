-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# VLC's native library looks up Java classes and members by name.
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
