# Room and libsu supply their own consumer rules.

# GoPay screen helper: started by name through app_process, never referenced from code.
-keep class net.wastu.artha.walk.GojekScreenHelper { public static void main(java.lang.String[]); }

# Ktor server rules for Android
-dontwarn java.lang.management.**
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
