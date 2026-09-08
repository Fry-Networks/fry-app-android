# Retrofit / OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt
-keep class dagger.hilt.** { *; }
-keepclassmembers,allowobfuscation class * {
    @dagger.hilt.android.AndroidEntryPoint <init>();
}

# Gson model classes used for Retrofit responses
-keep class com.frynetworks.fryapp.data.remote.** { *; }
