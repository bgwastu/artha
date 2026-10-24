# Room and libsu supply their own consumer rules.

# Xposed module entry: the framework locates it by assets/xposed_init and
# instantiates via reflection, so it must not be renamed or removed.
-keep public class net.wastu.artha.hook.ArthaHookMain { public *; }
-keep class net.wastu.artha.hook.** { public protected *; }
-keep class de.robv.android.xposed.** { *; }

# Ktor server rules for Android
-dontwarn java.lang.management.**
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
