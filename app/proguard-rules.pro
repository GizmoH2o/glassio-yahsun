-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keep class com.glasslauncher.tv.HomeKeyService { *; }
-keep class com.glasslauncher.tv.saver.GlassDreamService { *; }
-keepattributes *Annotation*
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-keep class dadb.** { *; }
# Live TV (ported from Zero, which kept its own code whole): names unchanged, nothing it reaches dropped.
-keep class com.glasslauncher.tv.live.** { *; }

# The big picture's trailer page calls these (HeroTrailer's bridge): kept as they are.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# LiteRT (depth effect): the native side calls into these classes.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# NewPipe Extractor (trailers)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.** { *; }
-dontwarn org.jsoup.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
