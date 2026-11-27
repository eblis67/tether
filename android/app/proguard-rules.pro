# Keep rules for the shrunk release build. What is reached by name must survive whole:
#
# - Our own code. It is small next to the libraries, and several pieces are only reached by
#   name: the manifest components, and above all the shell helper — app_process loads
#   app.tether.helper.HelperMain from this APK by its class name (HelperBridge), so renaming
#   or stripping it would silently kill call audio.
-keep class app.tether.** { *; }
-keep interface app.tether.** { *; }

# - The crypto providers are looked up by name (JCA) and bound by JNI name.
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-keep class org.conscrypt.** { *; }
-keep interface org.conscrypt.** { *; }

# libadb-android reflects into bundled sun.security classes.
-keep class sun.security.** { *; }
-dontwarn sun.security.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
