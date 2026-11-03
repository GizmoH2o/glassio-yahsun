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
