# Preserve the Gecko JNI/reflection boundary. Its prebuilt libxul is unchanged.
-keep class org.mozilla.** { *; }
-keep interface org.mozilla.** { *; }
# First optimized delivery retains application binary names and instrumentable members.
# Optimizations (including folding disabled diagnostics) are allowed; no profile migration.
-keep,allowoptimization class com.mekromn.bubble.** { *; }
-keep,allowoptimization interface com.mekromn.bubble.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
