package net.wastu.artha.hook;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ArthaHookMain implements IXposedHookLoadPackage {

    public static final String TAG = "ArthaHook";

    private static final Target[] TARGETS = {
        new Target("com.gojek.app",
            new String[] { "gopayapi.com", "gojekapi.com", "order-history", "order-detail", "payment-options" },
            "artha_gojek_capture.jsonl"),
        new Target("com.gojek.gopay",
            new String[] { "gopayapi.com", "gojekapi.com", "order-history", "payment-options" },
            "artha_gopay_capture.jsonl"),
        new Target("com.shopee.id",
            new String[] { "shopee.co.id", "/api/v2/", "/order" },
            "artha_shopee_capture.jsonl"),
    };

    private static final class Target {
        final String pkg; final String[] urlFilters; final String outFileName;
        Target(String p, String[] f, String o) { pkg=p; urlFilters=f; outFileName=o; }
    }

    private final AtomicLong seq = new AtomicLong();
    private static Method bridgeLog;
    private static Method bridgeHook;

    private static volatile boolean bodyHookInstalled;
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        maybeBridge(lpparam.classLoader);
        for (Target t : TARGETS) {
            if (!t.pkg.equals(lpparam.packageName)) continue;
            install(lpparam, t);
            return;
        }
    }

    private static void maybeBridge(ClassLoader targetLoader) {
        if (bridgeHook != null) return;
        ClassLoader[] loaders = { ArthaHookMain.class.getClassLoader(), targetLoader };

        for (ClassLoader loader : loaders) {
            try { android.util.Log.i(TAG, "[diag] trying loader " + loader); } catch (Throwable ignored) {}

            String[] names = { "de.robv.android.xposed.XposedBridge", "jbn.cVltwUuGgmbcKtQK.q.XposedBridge" };
            for (String n : names) {
                try {
                    Class<?> c = Class.forName(n, false, loader == null ? XposedBridgeCxl.class.getClassLoader() : loader);
                    try { android.util.Log.i(TAG, "[diag] " + n + " with " + c.getDeclaredMethods().length + " methods"); } catch (Throwable ignored) {}
                    for (Method m2 : c.getDeclaredMethods()) {
                        String sig = m2.getName() + "(";
                        for (Class tt : m2.getParameterTypes()) sig += tt.getSimpleName() + ",";
                        sig += ")";
                        try { android.util.Log.i(TAG, "[diag] m " + sig); } catch (Throwable ignored) {}
                    }
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (bridgeLog == null && "log".equals(m.getName()) && p.length == 1 && p[0] == String.class) bridgeLog = m;
                        if (bridgeHook == null
                            && ("hookMethod".equals(m.getName()) || "hookAllMethods".equals(m.getName()))
                            && p.length >= 2
                            && java.lang.reflect.Member.class.isAssignableFrom(p[0])
                            && p[1] != null && p[1].isAssignableFrom(XC_MethodHook.class)) { bridgeHook = m; }
                        if (bridgeLog != null && bridgeHook != null) return;
                    }
                } catch (Throwable ignored) {}
            }
            if (bridgeHook != null) return;
        }
    }

    private static final class XposedBridgeCxl {}

    /**
     * Vector renames the de.robv.* legacy bridge per boot (e.g. jbn.*... today, wUHnPe.* tomorrow),
     * so the hard-coded candidate names in maybeBridge() can miss. Our probe callback's runtime
     * superclass carries the renamed XC_MethodHook name, and its package hosts the sibling
     * XposedBridge class — derive both from the probe instead of guessing.
     */
    private static void maybeBridgeFromProbe() {
        if (bridgeHook != null) return;
        try {
            XC_MethodHook probe = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {}
            };
            Class<?> base = probe.getClass().getSuperclass();
            int dot = (base == null) ? -1 : base.getName().lastIndexOf('.');
            if (dot <= 0) return;
            String pkg = base.getName().substring(0, dot);
            Class<?> xb = Class.forName(pkg + ".XposedBridge", false, ArthaHookMain.class.getClassLoader());
            for (Method m : xb.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (bridgeHook == null && "hookMethod".equals(m.getName())
                        && p.length >= 2 && java.lang.reflect.Member.class.isAssignableFrom(p[0])
                        && p[1] != null && p[1].isAssignableFrom(probe.getClass())) bridgeHook = m;
                if (bridgeLog == null && "log".equals(m.getName()) && p.length == 1 && p[0] == String.class) bridgeLog = m;
                if (bridgeHook != null && bridgeLog != null) break;
            }
            log("[artha] bridge from probe " + pkg + " hookMethod=" + (bridgeHook != null) + " log=" + (bridgeLog != null));
        } catch (Throwable t) { log("[artha] bridge probe failed " + t); }
    }

    private static void log(String s) {
        if (bridgeLog != null) { try { bridgeLog.invoke(null, s); return; } catch (Throwable ignored) {} }
        try { android.util.Log.i(TAG, s); } catch (Throwable ignored) {}
    }

    private void install(XC_LoadPackage.LoadPackageParam lpparam, Target target) {
        final File out = new File(lpparam.appInfo.dataDir, "files/" + target.outFileName);
        log("[artha] arm " + target.pkg);
        try {
            if (bridgeHook == null) maybeBridgeFromProbe();
            Class<?> chain = Class.forName("okhttp3.internal.http.RealInterceptorChain", false, lpparam.classLoader);
            int n = 0;
            for (Method m : chain.getDeclaredMethods()) {
                if (!"proceed".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || !p[0].getName().endsWith("Request")) continue;
                Object cb = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try { capture(target, out, param.args[0], param.getResult()); } catch (Throwable ignored) {}
                    }
                };
                if (bridgeHook != null) bridgeHook.invoke(null, m, cb);
                else XposedBridge.hookMethod(m, (XC_MethodHook) cb);
                n++;
            }
            log("[artha] hooked " + n);
        } catch (Throwable t) { log("[artha] install failed " + t); }
    }

    private boolean want(Target t, String u) {
        if (u == null) return false;
        for (String f : t.urlFilters) if (u.contains(f)) return true;
        return false;
    }

    private void capture(Target t, File out, Object req, Object resp) {
        if (req == null || resp == null) return;
        try {
            String url = String.valueOf(one(req, "url"));
            if (!want(t, url)) return;
            String method = String.valueOf(one(req, "method"));
            int code = ((Number) one(resp, "code")).intValue();
            StringBuilder rq = new StringBuilder(); heads(req, "headers", rq);
            StringBuilder rs = new StringBuilder(); heads(resp, "headers", rs);
            byte[] rb = bodyOf(req); byte[] sb = peekBody(resp, 262144);
            logJson(out, t.pkg, code, method, url, rq.toString(), rb, rs.toString(), sb);
        } catch (Throwable ignored) {}
    }

    private static final long MAX_CAPTURE_BYTES = 12L * 1024 * 1024;

    private static void logJson(File out, String target, int code, String method, String url,
                               String reqHead, byte[] reqBody, String respHead, byte[] respBody) {
        // Rotate before appending so the capture file stays bounded for Artha's reader.
        if (out.length() > MAX_CAPTURE_BYTES) {
            File previous = new File(out.getParentFile(), out.getName() + ".1");
            try { if (previous.exists()) previous.delete(); } catch (Throwable ignored) {}
            try { out.renameTo(previous); } catch (Throwable ignored) {}
        }
        try (FileOutputStream fo = new FileOutputStream(out, true)) {
            StringBuilder s = new StringBuilder(4096);
            s.append("{\"seq\":").append(seqId.incrementAndGet())
             .append(",\"ts\":").append(System.currentTimeMillis())
             .append(",\"target\":\"").append(target).append('"')
             .append(",\"code\":").append(code)
             .append(",\"method\":\"").append(method).append('"')
             .append(",\"url\":\"").append(esc(url)).append('"')
             .append(",\"req_headers\":\"").append(esc(reqHead)).append('"')
             .append(",\"req_body\":").append(escBody(reqBody))
             .append(",\"resp_headers\":\"").append(esc(respHead)).append('"')
             .append(",\"resp_body\":").append(escBody(respBody))
             .append("}\n");
            fo.write(s.toString().getBytes(StandardCharsets.UTF_8));
            fo.flush();
            try { fo.getFD().sync(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static final AtomicLong seqId = new AtomicLong();

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') o.append("\\\"");
            else if (c == '\\') o.append("\\\\");
            else if (c == '\n') o.append("\\n");
            else if (c == '\r') o.append("\\r");
            else if (c == '\t') o.append("\\t");
            else if (c < 0x20 || c > 0x7e) o.append(String.format("\\u%04x", (int) c));
            else o.append(c);
        }
        return o.toString();
    }

    private static String escBody(byte[] b) {
        if (b == null || b.length == 0) return "null";
        return "\"" + esc(new String(b, StandardCharsets.UTF_8)) + "\"";
    }

    /** Generic name-based helper mirroring XposedHelpers.callMethod(); */
    private static Object one(Object receiver, String m) throws Exception {
        for (Method mm : receiver.getClass().getDeclaredMethods()) {
            if (!mm.getName().equals(m)) continue;
            if (mm.getParameterTypes().length != 0) continue;
            mm.setAccessible(true);
            try { return mm.invoke(receiver); }
            catch (java.lang.reflect.InvocationTargetException e) { Throwable c = e.getCause(); if (c instanceof RuntimeException) throw (RuntimeException) c; if (c instanceof Exception) throw (Exception) c; if (c == null) throw e; throw new RuntimeException(c); }
        }
        for (Method mm : receiver.getClass().getMethods()) {
            if (!mm.getName().equals(m)) continue;
            if (mm.getParameterTypes().length != 0) continue;
            try { return mm.invoke(receiver); }
            catch (java.lang.reflect.InvocationTargetException e) { Throwable c = e.getCause(); if (c instanceof RuntimeException) throw (RuntimeException) c; if (c instanceof Exception) throw (Exception) c; if (c == null) throw e; throw new RuntimeException(c); }
        }
        throw new NoSuchMethodException(receiver.getClass() + "." + m);
    }

    private static Object two(Object receiver, String m, int idx) throws Exception {
        Class<?>[] signatures = { int.class };
        for (Method mm : receiver.getClass().getDeclaredMethods()) {
            if (!mm.getName().equals(m)) continue;
            Class<?>[] p = mm.getParameterTypes();
            if (p.length != 1 || !p[0].isPrimitive() || p[0] != int.class) continue;
            mm.setAccessible(true);
            try { return mm.invoke(receiver, idx); }
            catch (java.lang.reflect.InvocationTargetException e) { Throwable c = e.getCause(); if (c instanceof RuntimeException) throw (RuntimeException) c; if (c instanceof Exception) throw (Exception) c; if (c == null) throw e; throw new RuntimeException(c); }
        }
        for (Method mm : receiver.getClass().getMethods()) {
            if (!mm.getName().equals(m)) continue;
            Class<?>[] p = mm.getParameterTypes();
            if (p.length != 1 || p[0] != int.class) continue;
            mm.setAccessible(true);
            try { return mm.invoke(receiver, idx); }
            catch (java.lang.reflect.InvocationTargetException e) { Throwable c = e.getCause(); if (c instanceof RuntimeException) throw (RuntimeException) c; if (c instanceof Exception) throw (Exception) c; if (c == null) throw e; throw new RuntimeException(c); }
        }
        throw new NoSuchMethodException(receiver.getClass() + "." + m + "(int)");
    }

    private static void heads(Object reqOrResp, String headerField, StringBuilder out) {
        try {
            Object hs = one(reqOrResp, headerField);
            Object sizeO = one(hs, "size");
            int n = (sizeO instanceof Number) ? ((Number) sizeO).intValue() : 0;
            for (int i = 0; i < n; i++) {
                String name = String.valueOf(two(hs, "name", i));
                String val = String.valueOf(two(hs, "value", i));
                out.append(name).append(": ").append(val).append("\n");
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] bodyOf(Object req) {
        try {
            Object body = one(req, "body");
            if (body == null) return null;
            Class<?> bufCls = Class.forName("okio.Buffer", false, body.getClass().getClassLoader());
            Object buf = bufCls.getDeclaredConstructor().newInstance();
            // locate writeTo(BufferedSink) and invoke with our buffer
            for (Method mm : body.getClass().getDeclaredMethods()) {
                if (!mm.getName().equals("writeTo")) continue;
                Class<?>[] p = mm.getParameterTypes();
                if (p.length != 1 || !p[0].isAssignableFrom(bufCls)) continue;
                mm.setAccessible(true);
                mm.invoke(body, buf);
                Object s = one(buf, "readUtf8");
                return String.valueOf(s).getBytes(StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object newInstanceOf(String clsName, ClassLoader cl) throws Exception {
        Class<?> c = Class.forName(clsName, false, cl);
        return c.getDeclaredConstructor().newInstance();
    }

    private static byte[] peekBody(Object resp, int max) {
        for (String name : new String[] { "peekBody" }) {
            try {
                for (Method mm : resp.getClass().getDeclaredMethods()) {
                    if (!mm.getName().equals(name)) continue;
                    Class<?>[] p = mm.getParameterTypes();
                    if (p.length != 1) continue;
                    mm.setAccessible(true);
                }
                Object rb = null;
                try { rb = intCall(resp, name, max); }
                catch (Throwable t1) {
                    try { rb = longCall(resp, name, (long) max); } catch (Throwable t2) { continue; }
                }
                if (rb == null) continue;
                return String.valueOf(one(rb, "string")).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object longCall(Object receiver, String m, long v) throws Exception {
        for (Method mm : receiver.getClass().getDeclaredMethods()) {
            if (!mm.getName().equals(m)) continue;
            Class<?>[] p = mm.getParameterTypes();
            if (p.length != 1 || p[0] != long.class) continue;
            mm.setAccessible(true);
            return mm.invoke(receiver, v);
        }
        throw new NoSuchMethodException(receiver.getClass() + "." + m + "(long)");
    }

    private static Object intCall(Object receiver, String m, int v) throws Exception {
        for (Method mm : receiver.getClass().getDeclaredMethods()) {
            if (!mm.getName().equals(m)) continue;
            Class<?>[] p = mm.getParameterTypes();
            if (p.length != 1 || p[0] != int.class) continue;
            mm.setAccessible(true);
            return mm.invoke(receiver, v);
        }
        throw new NoSuchMethodException(receiver.getClass() + "." + m + "(int)");
    }
}
