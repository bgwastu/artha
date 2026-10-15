package net.wastu.artha.data.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.system.Os;
import java.lang.reflect.Method;

/**
 * Root-privileged helper to acquire a Google Drive OAuth token by running as an installed
 * first-party Google application (e.g. Google Drive or Gmail) via app_process.
 */
public class DriveAuthHelper {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: DriveAuthHelper <email> [uid] [packageName]");
            System.exit(1);
        }
        final String email = args[0];
        final int uid = args.length > 1 ? Integer.parseInt(args[1]) : 10290;
        final String pkg = args.length > 2 ? args[2] : "com.google.android.apps.docs";

        try {
            Os.setuid(uid);
            Looper.prepareMainLooper();

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method sysInit = atClass.getDeclaredMethod("systemMain");
            Object at = sysInit.invoke(null);
            Method getSysCtx = atClass.getDeclaredMethod("getSystemContext");
            Context ctx = (Context) getSysCtx.invoke(at);
            Context appCtx = ctx.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);

            final AccountManager am = AccountManager.get(appCtx);
            final Account acct = new Account(email, "com.google");
            final Looper looper = Looper.myLooper();

            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        AccountManagerFuture<Bundle> fut =
                            am.getAuthToken(acct, "oauth2:https://www.googleapis.com/auth/drive", null, false, null, null);
                        Bundle res = fut.getResult();
                        String token = res.getString(AccountManager.KEY_AUTHTOKEN);
                        if (token != null && !token.trim().isEmpty()) {
                            System.out.println("AUTH_TOKEN:" + token.trim());
                            System.exit(0);
                        } else {
                            System.err.println("No auth token in result");
                            System.exit(2);
                        }
                    } catch (Throwable t) {
                        System.err.println("Token acquisition error: " + t.getMessage());
                        System.exit(3);
                    } finally {
                        if (looper != null) {
                            looper.quitSafely();
                        }
                    }
                }
            });
            worker.start();
            Looper.loop();
        } catch (Throwable t) {
            System.err.println("Fatal: " + t.getMessage());
            System.exit(4);
        }
    }
}
