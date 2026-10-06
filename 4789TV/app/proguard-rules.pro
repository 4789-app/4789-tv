# 4789 TV release rules. The receiver's media stack, the wire protocol and the data layer
# serialise by reflection or through JNI; keep their shapes.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.fourseveneightnine.**$$serializer { *; }
-keepclassmembers class com.fourseveneightnine.** { *** Companion; }
-keepclasseswithmembers class com.fourseveneightnine.** { kotlinx.serialization.KSerializer serializer(...); }
-keep @kotlinx.serialization.Serializable class com.fourseveneightnine.** { *; }

# Ktor server (CIO) and its reflection
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**

# libmpv JNI, media3 ffmpeg extension, SGSR effect (reflected into media3-effect)
-keep class dev.jdtech.mpv.** { *; }
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.effect.** { *; }
-keep class com.fourseveneightnine.tv.player.upscale.** { *; }
-dontwarn androidx.media3.**

# Room, WorkManager, Coil
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.** { *; }
-dontwarn coil3.**

# ZXing
-keep class com.google.zxing.** { *; }

# Tink (Ed25519 verify in :contract)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
