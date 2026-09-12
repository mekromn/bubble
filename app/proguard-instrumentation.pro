# Only a CLASS-retained compiler annotation in Error Prone refers to this
# JDK compiler enum. Android never evaluates that annotation at runtime.
# Scope this exception to the instrumentation APK, not the delivered browser.
-dontwarn javax.lang.model.element.Modifier
# The test runner selects these classes by fully qualified command-line name.
-keep class com.mekromn.bubble.** { *; }
-keepattributes *Annotation*
