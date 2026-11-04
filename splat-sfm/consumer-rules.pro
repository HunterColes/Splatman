# JNI: the native side looks these up by name (FindClass) and binds the external functions by their Java names.
-keep class com.huntercoles.splatman.splat.sfm.nativebridge.NativeSfm { *; }
-keep class com.huntercoles.splatman.splat.sfm.nativebridge.NativeSfmException { <init>(java.lang.String); }
