/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 *
 * Copied into frameworks/base/services/core/java/com/android/server/connectivity by
 * scripts/apply.py. Design: docs/design/VPN.md.
 */
package com.android.server.connectivity;

import android.content.Context;
import android.database.ContentObserver;
import android.net.INetd;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.SparseArray;

import com.android.internal.os.BackgroundThread;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The Secure Folder and the main side's VPN.
 *
 * <ul>
 *   <li>A Secure Folder with no VPN of its own is covered by its owner's VPN, so its apps never go
 *       straight onto the network while the main side has a VPN. Lockdown ("Block connections
 *       without VPN") covers it too.</li>
 *   <li>A VPN started inside the folder is carried by the owner's VPN: the folder's VPN app is the
 *       only folder app the owner's VPN still covers, and netd sends that app's protected tunnel
 *       sockets through the owner's VPN instead of the bare network (property {@link #PROP}).
 *       If the owner's VPN is always-on but down, the tunnel goes nowhere: fails closed.</li>
 * </ul>
 *
 * Both depend on the owner's per-user secure setting {@link #SETTING}, on (1) unless set to 0.
 */
public final class AnonVpnRoute {
    static final String SETTING = "anon_folder_via_main_vpn";
    /** "uid:netId,..." read by netd's FwmarkServer for VPN apps' protected sockets. */
    static final String PROP = "anon.vpn.carried";

    private static final SparseArray<WeakReference<Vpn>> sVpns = new SparseArray<>();
    /** Folder user -> package of the VPN connected inside it. A snapshot, so no Vpn lock is needed. */
    private static volatile SparseArray<String> sActive = new SparseArray<>();
    private static volatile Context sContext;
    private static final Runnable sRefresh = AnonVpnRoute::refresh;

    private AnonVpnRoute() {}

    public static void register(Context ctx, int userId, Vpn vpn) {
        boolean first = sContext == null;
        sContext = ctx;
        synchronized (sVpns) {
            sVpns.put(userId, new WeakReference<>(vpn));
        }
        if (first) {
            Handler h = BackgroundThread.getHandler();
            ctx.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(SETTING),
                    false, new ContentObserver(h) {
                        @Override
                        public void onChange(boolean selfChange) {
                            changed();
                        }
                    }, UserHandle.USER_ALL);
        }
        changed();
    }

    public static void unregister(int userId) {
        synchronized (sVpns) {
            sVpns.remove(userId);
        }
        changed();
    }

    /** Any VPN started, stopped or changed: recompute, once, off the caller's thread and locks. */
    static void changed() {
        Handler h = BackgroundThread.getHandler();
        h.removeCallbacks(sRefresh);
        h.post(sRefresh);
    }

    // ---- Asked by Vpn.createUserAndRestrictedProfilesRanges ----

    /** The Secure Folders whose traffic {@code userId}'s VPN carries (all, or just their VPN app). */
    static List<Integer> carriedFolders(Context ctx, int userId) {
        List<Integer> out = new ArrayList<>();
        if (!on(ctx, userId)) return out;
        final long token = Binder.clearCallingIdentity();
        try {
            UserManager um = ctx.getSystemService(UserManager.class);
            for (int id : um.getProfileIds(userId, false)) {
                if (id != userId && isFolder(um, id)) out.add(id);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return out;
    }

    /**
     * The package of the VPN running inside {@code folder}, or null. While one runs, the owner's
     * VPN covers only that package (its tunnel); the folder's own VPN covers the rest.
     */
    static String folderVpnPackage(int folder) {
        return sActive.get(folder);
    }

    /** True when {@code userId} is a Secure Folder whose VPN is carried by its owner's VPN. */
    static boolean chainsOut(Context ctx, int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            UserManager um = ctx.getSystemService(UserManager.class);
            if (!isFolder(um, userId)) return false;
            UserHandle parent = um.getProfileParent(UserHandle.of(userId));
            return parent != null && on(ctx, parent.getIdentifier());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // ---- Recompute ----

    private static void refresh() {
        Context ctx = sContext;
        if (ctx == null) return;
        List<Vpn> all = new ArrayList<>();
        synchronized (sVpns) {
            for (int i = 0; i < sVpns.size(); i++) {
                Vpn v = sVpns.valueAt(i).get();
                if (v != null) all.add(v);
            }
        }
        UserManager um = ctx.getSystemService(UserManager.class);
        // 1. Snapshot which VPN app each folder has set up and connected, one Vpn lock at a time.
        SparseArray<String> active = new SparseArray<>();
        SparseArray<String> prepared = new SparseArray<>();
        for (Vpn v : all) {
            int user = v.anonUserId();
            if (!isFolder(um, user)) continue;
            String[] pkgs = v.anonPackages();
            if (pkgs[0] != null) prepared.put(user, pkgs[0]);
            if (pkgs[1] != null) active.put(user, pkgs[1]);
        }
        sActive = active;
        // 2. Every VPN recomputes the apps it covers from that snapshot.
        for (Vpn v : all) v.anonRefreshRanges();
        // 3. A folder VPN that's set up (connected or not) has its tunnel carried by the owner's VPN.
        StringBuilder carried = new StringBuilder();
        for (Vpn v : all) {
            int user = v.anonUserId();
            if (prepared.get(user) == null || !chainsOut(ctx, user)) continue;
            UserHandle parent = um.getProfileParent(UserHandle.of(user));
            Vpn owner = parent == null ? null : vpnOf(parent.getIdentifier());
            int net = owner == null ? 0 : owner.anonNetId();
            if (net == 0 && owner != null && owner.getAlwaysOnPackage() != null) {
                // The main VPN is meant to be on but isn't up: the folder's tunnel goes nowhere.
                net = INetd.UNREACHABLE_NET_ID;
            }
            if (net == 0) continue;
            if (carried.length() > 0) carried.append(',');
            carried.append(v.anonOwnerUid()).append(':').append(net);
        }
        SystemProperties.set(PROP, carried.toString());
    }

    private static Vpn vpnOf(int userId) {
        synchronized (sVpns) {
            WeakReference<Vpn> r = sVpns.get(userId);
            return r == null ? null : r.get();
        }
    }

    private static boolean isFolder(UserManager um, int userId) {
        android.content.pm.UserInfo info = um.getUserInfo(userId);
        return info != null && info.isPrivateProfile();
    }

    private static boolean on(Context ctx, int userId) {
        return Settings.Secure.getIntForUser(ctx.getContentResolver(), SETTING, 1, userId) != 0;
    }
}
