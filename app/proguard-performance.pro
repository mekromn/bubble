# Preserve the Gecko JNI/reflection boundary. Its prebuilt libxul is unchanged.
-keep class org.mozilla.** { *; }
-keep interface org.mozilla.** { *; }
# First optimized delivery retains application binary names and instrumentable members.
# Optimizations (including folding disabled diagnostics) are allowed; no profile migration.
-keep,allowoptimization class com.mekromn.bubble.** { *; }
-keep,allowoptimization interface com.mekromn.bubble.** { *; }
# Android instrumentation shares these dependencies with the target APK. Its
# runner uses e.g. androidx.tracing.Trace even when the browser has inlined it.
# Keep binary API names/members across the two APKs; still optimize method bodies.
-keep,allowoptimization class androidx.** { *; }
-keep,allowoptimization interface androidx.** { *; }
# Kotlin runtime names are also shared with the separately compiled test runner.
# Preserve its binary ABI; keep optimization enabled in method bodies.
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization interface kotlin.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
