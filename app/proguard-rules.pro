# Boswell Phone release shrinking (R8).

# sherpa-onnx: its JNI code reads the Kotlin config classes' fields by name and
# constructs result classes from native code, so nothing in it may be renamed.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime's Java API is the same: native code finds classes and fields by name.
-keep class ai.onnxruntime.** { *; }

# Concentus (Opus encode/decode in Java) uses no reflection, but keep it whole:
# it's small and bit-exact parity with libopus was measured on this code.
-keep class org.concentus.** { *; }

# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class net.boswell.phone.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.boswell.phone.**$$serializer { *; }

# Stack traces in the log stay readable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# JavaMail finds its protocol providers (IMAP, SMTP) and data handlers by
# class name, from META-INF/javamail.* files.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.**

# Google's code scanner (ML Kit) finds its components by name at runtime; shrinking
# them made GmsBarcodeScanning.getClient() crash with a NullPointerException.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
