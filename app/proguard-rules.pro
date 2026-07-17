# R8 keep rules for the release build (Phase 7). Most libraries here ship their own consumer
# rules (Room, Coil, OkHttp, ktor, AndroidX); these cover the reflective seams those don't, plus
# our own reflectively-(de)serialised model classes. If a release build crashes at runtime on a
# ClassNotFound/NoSuchMethod in one of these areas, widen the matching rule below.

# ── kotlinx.serialization ────────────────────────────────────────────────────
# The compiler generates a synthetic $$serializer for every @Serializable class; keep them and the
# Companion.serializer() accessor. Without this, supabase-kt request/response models fail at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Our own @Serializable models (sync + analytics transport).
-keep @kotlinx.serialization.Serializable class com.wildodds.gymtracker.** { *; }

# ── Gson (reflective, no annotations) ────────────────────────────────────────
# BackupManager / AccountExporter (de)serialise these by field reflection.
-keep class com.wildodds.gymtracker.data.sync.TrainingSnapshot { *; }
-keep class com.wildodds.gymtracker.data.account.AccountExport { *; }
-keep class com.wildodds.gymtracker.data.account.AccountExport$* { *; }
-keep class com.wildodds.gymtracker.data.db.entity.** { *; }
# Gson type-token machinery.
-keepattributes Signature, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── supabase-kt / ktor ───────────────────────────────────────────────────────
# ktor uses service loaders + reflection for engine selection; keep the OkHttp engine and models.
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ── Google Credential Manager / Sign-in ──────────────────────────────────────
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**

# ── Kotlin coroutines / metadata (defensive) ─────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── PdfBox-Android optional JPEG2000 codec (not bundled) ──────────────────────
# JPXFilter references an optional JP2 decoder we don't ship; the AI file-text path never needs it.
-dontwarn com.gemalto.jp2.JP2Decoder
