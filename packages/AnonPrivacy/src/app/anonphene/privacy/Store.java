/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.privacy;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import app.anonphene.sharelist.ShareList;

/** Reads and writes this user's share list and the related switches in Settings.Secure. */
final class Store {
    private Store() {}

    static ShareList shareList(Context ctx) {
        return ShareList.parse(Settings.Secure.getString(ctx.getContentResolver(), ShareList.SETTING));
    }

    static void save(Context ctx, ShareList list) {
        String text = list.serialize();
        Settings.Secure.putString(ctx.getContentResolver(), ShareList.SETTING, text.isEmpty() ? null : text);
    }

    /** Settings.Secure keys read by AnonpheneOS's telephony and network-policy hooks. */
    static final String PHONE_NUMBER_BLANK = "anon_phone_number_blank";
    static final String MOBILE_DATA_ALLOWED = "anon_mobile_data_allowed";
    static final String FOLDER_VIA_MAIN_VPN = "anon_folder_via_main_vpn";

    /** On unless switched off: the Secure Folder, and any VPN inside it, go through the main VPN. */
    static boolean folderViaMainVpn(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), FOLDER_VIA_MAIN_VPN, 1) != 0;
    }

    static void setFolderViaMainVpn(Context ctx, boolean on) {
        Settings.Secure.putInt(ctx.getContentResolver(), FOLDER_VIA_MAIN_VPN, on ? 1 : 0);
    }

    /** A comma-separated package list setting, as a set. */
    static java.util.Set<String> packages(Context ctx, String key) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        String s = Settings.Secure.getString(ctx.getContentResolver(), key);
        if (s != null) for (String p : s.split(",")) if (ShareList.isPackageName(p.trim())) out.add(p.trim());
        return out;
    }

    static void setPackages(Context ctx, String key, java.util.Set<String> pkgs) {
        Settings.Secure.putString(ctx.getContentResolver(), key, pkgs.isEmpty() ? null : String.join(",", new java.util.TreeSet<>(pkgs)));
    }

    static boolean hidePeople(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), ShareList.SETTING_HIDE_PEOPLE, 1) != 0;
    }

    static void setHidePeople(Context ctx, boolean hide) {
        Settings.Secure.putInt(ctx.getContentResolver(), ShareList.SETTING_HIDE_PEOPLE, hide ? 1 : 0);
    }

    /**
     * Defaults that hold from the first boot: people hidden in the share sheet, Secure Folder
     * hidden from the launcher and locking with the phone, and Android's dropbox (its store of
     * crash and error reports) kept to almost nothing. Called at every boot; values you changed
     * stay changed, except the dropbox limits, which aren't a choice in AnonpheneOS.
     */
    static void applyDefaults(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        if (Settings.Secure.getString(cr, ShareList.SETTING_HIDE_PEOPLE) == null) {
            Settings.Secure.putInt(cr, ShareList.SETTING_HIDE_PEOPLE, 1);
        }
        Settings.Secure.putInt(cr, "hide_privatespace_entry_point", 1);
        if (Settings.Secure.getString(cr, "private_space_auto_lock") == null) {
            Settings.Secure.putInt(cr, "private_space_auto_lock", 0);
        }
        Settings.Global.putInt(cr, "dropbox_age_seconds", 60);
        Settings.Global.putInt(cr, "dropbox_max_files", 1);
        Settings.Global.putInt(cr, "dropbox_quota_kb", 64);
        Settings.Global.putInt(cr, "dropbox_quota_percent", 0);
    }
}
