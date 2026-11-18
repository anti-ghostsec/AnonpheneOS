/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 *
 * Copied into frameworks/base/services/core/java/com/android/server/net by scripts/apply.py.
 */
package com.android.server.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.util.Set;

/**
 * AnonpheneOS's per-app "Mobile data" permission, off by default.
 *
 * <p>An installed app may use mobile data only once you allow it (Settings → AnonpheneOS privacy
 * → Mobile data). Until then it uses Wi-Fi, or nothing. Off mobile data, your carrier never
 * carries the app's traffic, so it can't attach your phone number to it or confirm your number to
 * the app's server ("silent number verification"). Apps that come with the system are allowed, so
 * calls, texts, MMS and the carrier's own services keep working.
 *
 * <p>NetworkPolicyManagerService asks {@link #blocks} wherever it checks the mobile-data
 * restrictions a device administrator can set, so the same enforcement applies: metered networks
 * are closed to the app in the foreground and the background. The allowed list is the per-user
 * secure setting {@link #SETTING}, package names separated by commas.
 */
final class AnonMobileData {
    static final String SETTING = "anon_mobile_data_allowed";

    private static volatile Context sContext;
    private static final SparseArray<Set<String>> sAllowed = new SparseArray<>();
    private static final SparseBooleanArray sBlocked = new SparseBooleanArray();

    private AnonMobileData() {}

    /** Called once NetworkPolicyManagerService is ready; {@code recompute} redoes every uid's rules. */
    static void start(Context ctx, Handler handler, Runnable recompute) {
        sContext = ctx;
        ContentObserver settings = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                forget();
                recompute.run();
            }
        };
        ctx.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SETTING), false, settings, UserHandle.USER_ALL);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        ctx.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                forget();
                recompute.run();
            }
        }, packages, null, handler);
        handler.post(recompute);
    }

    /** True when {@code uid} may not use mobile data. */
    static boolean blocks(int uid) {
        Context ctx = sContext;
        if (ctx == null || UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) return false;
        synchronized (sBlocked) {
            int i = sBlocked.indexOfKey(uid);
            if (i >= 0) return sBlocked.valueAt(i);
        }
        boolean blocked = decide(ctx, uid);
        synchronized (sBlocked) {
            sBlocked.put(uid, blocked);
        }
        return blocked;
    }

    private static boolean decide(Context ctx, int uid) {
        PackageManager pm = ctx.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) return false;
        int userId = UserHandle.getUserId(uid);
        Set<String> allowed = allowedFor(ctx, userId);
        for (String pkg : pkgs) {
            if (allowed.contains(pkg)) return false;
            try {
                ApplicationInfo ai = pm.getApplicationInfoAsUser(pkg, 0, userId);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    return false;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // gone; the next package decides
            }
        }
        return true;
    }

    private static Set<String> allowedFor(Context ctx, int userId) {
        synchronized (sAllowed) {
            Set<String> s = sAllowed.get(userId);
            if (s != null) return s;
        }
        Set<String> s = new ArraySet<>();
        String text = Settings.Secure.getStringForUser(ctx.getContentResolver(), SETTING, userId);
        if (text != null) {
            for (String p : text.split(",")) {
                if (!p.trim().isEmpty()) s.add(p.trim());
            }
        }
        synchronized (sAllowed) {
            sAllowed.put(userId, s);
        }
        return s;
    }

    private static void forget() {
        synchronized (sAllowed) {
            sAllowed.clear();
        }
        synchronized (sBlocked) {
            sBlocked.clear();
        }
    }
}
