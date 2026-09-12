# Preserve the Gecko JNI/reflection boundary. Its prebuilt libxul is unchanged.
-keep class org.mozilla.** { *; }
-keep interface org.mozilla.** { *; }
# Gecko DebugConfig invokes SnakeYAML at startup. TypeDescription relies on its
# Package metadata and the parser constructs Java objects reflectively. R8
# flattening TypeDescription into the default package caused an actual startup
# crash in the optimized Android test. Preserve this startup-only dependency's
# package/class/member names and constructors, not just its public entry point.
-keep class org.yaml.snakeyaml.** { *; }
-keep interface org.yaml.snakeyaml.** { *; }
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
