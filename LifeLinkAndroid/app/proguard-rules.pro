# LifeLink R8 / ProGuard rules.
#
# The release build currently ships with `isMinifyEnabled = false`, so these
# rules are inert today. They are committed now so that enabling R8 later is a
# one-line change (`isMinifyEnabled = true`) rather than a debugging session.
# They are also exercised by `./gradlew assembleRelease` in CI.

# --- Kotlin / coroutines -----------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- Retrofit -----------------------------------------------------------------
# Retrofit builds proxies from the interface annotations at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# --- Gson ---------------------------------------------------------------------
# Gson reflects over the DTO fields, so their names must survive.
-keepattributes *Annotation*
-keep class com.lifelink.app.data.remote.** { *; }
# The Supabase auth DTOs live in core.auth, not data.remote. Without this keep
# rule R8 renames their fields in the release build, so Gson serializes the
# request as {"a":"...","b":"..."} instead of {"email":"...","password":"..."}.
# Supabase then rejects the call with "missing email or phone" even though the
# form sent a valid address. Keep the whole package so every auth request and
# response DTO (AuthRequest, RefreshRequest, SupabaseAuthResponse, ...) keeps
# its wire field names.
-keep class com.lifelink.app.core.auth.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn sun.misc.**

# --- OkHttp -------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room ---------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- Firebase / FCM -----------------------------------------------------------
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- MapLibre -----------------------------------------------------------------
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# --- App entry points ---------------------------------------------------------
-keep class com.lifelink.app.LifeLinkApplication { *; }
-keep class com.lifelink.app.MainActivity { *; }
-keep class com.lifelink.app.core.notifications.LifeLinkFirebaseMessagingService { *; }
