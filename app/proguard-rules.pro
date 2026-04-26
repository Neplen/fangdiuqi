# Add project specific ProGuard rules here.
-keep class com.amap.api.** { *; }
-keep class com.loc.api.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
