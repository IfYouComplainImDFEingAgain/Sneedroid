# Keep kotlinx.serialization generated serializers.
-keepclassmembers, allowobfuscation class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepattributes *Annotation*, InnerClasses
# OkHttp / Okio (no-op on Android, silences warnings)
-dontwarn okhttp3.**
-dontwarn okio.**
