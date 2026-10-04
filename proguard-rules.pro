-dontwarn
-dontshrink
-dontoptimize

# Keep the complete customer-facing binary API stable. Private implementation
# details can still be renamed by ProGuard.
-keepnames public class com.zqnt.sdk.client.**
-keepclassmembers public class com.zqnt.sdk.client.** {
    public protected *;
}
-keep public interface com.zqnt.sdk.client.** { *; }
-keep public enum com.zqnt.sdk.client.** { *; }

# CDI and configuration may inspect constructors, fields and annotations.
-keepclasseswithmembers,includedescriptorclasses class com.zqnt.sdk.client.** {
    @jakarta.inject.Inject <init>(...);
}
# Arc resolves injected members by the names stored in the Jandex index.
# Renaming these fields makes the index inconsistent with the bytecode.
-keepclassmembers class com.zqnt.sdk.client.** {
    @jakarta.inject.Inject *;
}

# The Jandex index (META-INF/jandex.idx) is built BEFORE ProGuard runs, so every class it names
# must keep that name: Quarkus loads classes by the indexed name. Seen live 2026-10-01: the private
# ClientCredentials$BearerInterceptor became ClientCredentials$a, and every Quarkus app using the SDK
# failed to start with ClassNotFoundException, because quarkus-grpc registers each indexed
# io.grpc.ClientInterceptor. Keep every class name in the SDK (it is a public library; renaming
# private classes hides nothing), and keep interceptors whole.
-keepnames class com.zqnt.sdk.client.**
-keep class com.zqnt.sdk.client.** implements io.grpc.ClientInterceptor { *; }

-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,MethodParameters,Exceptions

# Do not expose local source paths in stack traces.
-renamesourcefileattribute SourceFile
