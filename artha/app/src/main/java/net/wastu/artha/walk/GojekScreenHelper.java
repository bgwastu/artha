package net.wastu.artha.walk;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.SparseArray;
import androidx.annotation.RequiresApi;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root-side half of the GoPay screen walk, started from Artha's own APK with
 * {@code CLASSPATH=<apk> app_process / net.wastu.artha.walk.GojekScreenHelper}.
 *
 * <p>It owns an invisible, always-unlocked virtual display with its own focus, so Gojek runs
 * there without touching what the user sees, and reads that display through UiAutomation.
 * Commands arrive one per line on stdin; every reply is one line starting with {@code ok} or
 * {@code err}. Dumps use the {@code uiautomator dump} XML shape. EOF or the watchdog releases the
 * display, which destroys whatever Gojek showed on it.
 *
 * <p>It runs as root outside any app, where the hidden display and UiAutomation APIs are the
 * whole point, and its display flags only exist from Android 13.
 */
@RequiresApi(33)
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "WrongConstant"})
public final class GojekScreenHelper {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2340;
    private static final int DENSITY = 440;
    private static final long WATCHDOG_MILLIS = TimeUnit.MINUTES.toMillis(6);

    private static VirtualDisplay display;
    private static UiAutomation automation;

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(WATCHDOG_MILLIS); } catch (InterruptedException ignored) { return; }
            release();
            System.exit(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] cmd = line.trim().split(" ");
                String reply;
                try {
                    reply = handle(cmd);
                } catch (Throwable t) {
                    reply = "err " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).replace('\n', ' ');
                }
                System.out.println(reply);
                System.out.flush();
                if (cmd[0].equals("quit")) break;
            }
        } catch (Throwable ignored) {
            // Lost the parent: fall through to release.
        }
        release();
        System.exit(0);
    }

    private static String handle(String[] cmd) throws Throwable {
        switch (cmd[0]) {
            case "open": return "ok " + open();
            case "launch": return run("am", "start", "--display", id(), "--activity-exclude-from-recents",
                "-a", "android.intent.action.VIEW", "-d", cmd[1], "com.gojek.app");
            case "dump": return "ok " + dump();
            case "tap": return run("input", "-d", id(), "tap", cmd[1], cmd[2]);
            case "swipe": return run("input", "-d", id(), "swipe", cmd[1], cmd[2], cmd[3], cmd[4], cmd[5]);
            case "back": return run("input", "-d", id(), "keyevent", "4");
            case "quit": return "ok";
            default: return "err unknown command";
        }
    }

    private static String id() {
        if (display == null) throw new IllegalStateException("display not open");
        return String.valueOf(display.getDisplay().getDisplayId());
    }

    private static int open() throws Throwable {
        if (display != null) return display.getDisplay().getDisplayId();
        Context shell = systemContext().createPackageContext("com.android.shell", 0);
        DisplayManager manager = (DisplayManager) shell.getSystemService(Context.DISPLAY_SERVICE);
        HandlerThread frames = new HandlerThread("artha-frames");
        frames.start();
        ImageReader reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image != null) image.close();
        }, new Handler(frames.getLooper()));
        int flags = 1 /* PUBLIC */ | 8 /* OWN_CONTENT_ONLY */ | (1 << 6) /* SUPPORTS_TOUCH */
            | (1 << 8) /* DESTROY_CONTENT_ON_REMOVAL */ | (1 << 10) /* TRUSTED */ | (1 << 11) /* OWN_DISPLAY_GROUP */
            | (1 << 12) /* ALWAYS_UNLOCKED */ | (1 << 13) /* TOUCH_FEEDBACK_DISABLED */ | (1 << 14) /* OWN_FOCUS */;
        display = manager.createVirtualDisplay("artha-gopay", WIDTH, HEIGHT, DENSITY, reader.getSurface(), flags);
        if (display == null) throw new IllegalStateException("virtual display refused");
        automation = connect();
        return display.getDisplay().getDisplayId();
    }

    private static void release() {
        try { if (automation != null) UiAutomation.class.getMethod("disconnect").invoke(automation); } catch (Throwable ignored) { }
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        automation = null;
        display = null;
    }

    private static Context systemContext() throws Throwable {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = threadClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        Field current = threadClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        Field system = threadClass.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(thread, true);
        Method context = threadClass.getDeclaredMethod("getSystemContext");
        return (Context) context.invoke(thread);
    }

    private static UiAutomation connect() throws Throwable {
        HandlerThread looper = new HandlerThread("artha-automation");
        looper.start();
        Constructor<?> connectionConstructor = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor();
        connectionConstructor.setAccessible(true);
        Object connection = connectionConstructor.newInstance();
        Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
        UiAutomation result = null;
        for (Constructor<?> c : UiAutomation.class.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != 2 || p[1] != connectionInterface) continue;
            c.setAccessible(true);
            if (p[0] == Looper.class) result = (UiAutomation) c.newInstance(looper.getLooper(), connection);
            else if (p[0] == Context.class) result = (UiAutomation) c.newInstance(systemContext(), connection);
            if (result != null) break;
        }
        if (result == null) throw new IllegalStateException("UiAutomation constructor not found");
        Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES: the user's own accessibility services keep running.
        connect.invoke(result, 1);
        AccessibilityServiceInfo info = result.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        result.setServiceInfo(info);
        return result;
    }

    private static String dump() {
        if (automation == null) throw new IllegalStateException("display not open");
        try { UiAutomation.class.getMethod("clearCache").invoke(automation); } catch (Throwable ignored) { }
        StringBuilder xml = new StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">");
        SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows = all.get(display.getDisplay().getDisplayId());
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) node(root, xml, 0);
            }
        }
        return xml.append("</hierarchy>").toString();
    }

    private static void node(AccessibilityNodeInfo node, StringBuilder xml, int depth) {
        if (depth > 60) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.append("<node text=\"").append(escape(node.getText()))
            .append("\" resource-id=\"").append(escape(node.getViewIdResourceName()))
            .append("\" class=\"").append(escape(node.getClassName()))
            .append("\" package=\"").append(escape(node.getPackageName()))
            .append("\" content-desc=\"").append(escape(node.getContentDescription()))
            .append("\" clickable=\"").append(node.isClickable())
            .append("\" bounds=\"[").append(bounds.left).append(',').append(bounds.top).append("][")
            .append(bounds.right).append(',').append(bounds.bottom).append("]\">");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) node(child, xml, depth + 1);
        }
        xml.append("</node>");
    }

    private static String escape(CharSequence value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\n': out.append("&#10;"); break;
                case '\r': out.append("&#13;"); break;
                default: if (c >= 0x20 || c == '\t') out.append(c);
            }
        }
        return out.toString();
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "err timeout";
        }
        return process.exitValue() == 0 ? "ok" : "err exit " + process.exitValue();
    }
}
