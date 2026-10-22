package de.robv.android.xposed;

public final class XposedBridge {
    public static abstract class CopyOnWriteArrayList<T> extends java.util.concurrent.CopyOnWriteArrayList<T> {}
    public static Object hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) { return null; }
    public static void log(String text) {}
    public static void log(Throwable t) {}
    private XposedBridge() {}
}
