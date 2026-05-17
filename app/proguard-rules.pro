# Abu Zahra Tracker - ProGuard Rules
# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keep class com.abuzahra.tracker.** { *; }
-dontwarn com.google.firebase.**
-dontwarn kotlinx.coroutines.**
