# libadb and sun.security (X.509 extensions via OIDMap) use reflection. BouncyCastle is only
# used directly by libadb's pairing code, so R8 may shrink it.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.security.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**
-dontwarn android.sun.**
-dontwarn sun.**
