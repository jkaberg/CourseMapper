# Add project specific ProGuard rules here.
# MapLibre keeps its native JNI bindings - do not strip them.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Keep Hilt-generated components
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
