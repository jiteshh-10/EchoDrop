# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ═══ Room entities — keep all fields/constructors for reflection ═══
-keep class com.dev.echodrop.db.** { *; }
-keepclassmembers class com.dev.echodrop.db.** { *; }

# ═══ BLE callbacks — keep scan callback classes ═══
-keep class com.dev.echodrop.ble.** { *; }

# ═══ WorkManager workers — keep for reflection ═══
-keep class com.dev.echodrop.workers.** { *; }

# ═══ Service + BroadcastReceiver — keep for manifest ═══
-keep class com.dev.echodrop.service.** { *; }

# ═══ Transfer protocol — keep wire format classes ═══
-keep class com.dev.echodrop.transfer.** { *; }

# ═══ Models — keep for JSON/serialization ═══
-keep class com.dev.echodrop.models.** { *; }

# ═══ Timber — strip debug logs in release ═══
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
}

# ═══ Preserve line numbers for crash reports ═══
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile