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
