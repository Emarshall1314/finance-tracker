# ---- Release builds never log: strip every android.util.Log call (AppLog is already a no-op). ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# ---- SQLCipher: JNI looks up these classes by name. ----
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# ---- Questrade JSON models and type-safe navigation routes (kotlinx.serialization). ----
-keep,includedescriptorclasses class ca.emarshall.finance.net.questrade.**$$serializer { *; }
-keepclassmembers class ca.emarshall.finance.net.questrade.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep class ca.emarshall.finance.ui.*Route { *; }
-keep class ca.emarshall.finance.ui.*Route$$serializer { *; }
-keepclassmembers class ca.emarshall.finance.ui.*Route { *** Companion; }
-keep class ca.emarshall.finance.ui.*Route$Companion { *; }

# ---- Retrofit service interface (methods are read reflectively). ----
-keep,allowobfuscation interface ca.emarshall.finance.net.questrade.QuestradeApi
-keepclassmembers interface ca.emarshall.finance.net.questrade.QuestradeApi { *; }

# ---- PdfBox-Android: optional JPEG2000 codec and font/crypto bits we don't use. ----
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# ---- Tink / misc annotations ----
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# ---- Anthropic SDK (opt-in Claude coach). The SDK ships its own keep rules (META-INF/proguard);
# OpenTelemetry's compile-only AutoValue annotations aren't on the runtime classpath. ----
-dontwarn com.google.auto.value.**
