/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 *
 * Copied into packages/modules/IntentResolver by scripts/apply.py.
 */
package com.android.intentresolver;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.Settings;

import app.anonphene.sharelist.ShareList;


/**
 * Cuts the share sheet's app list down to the share list chosen in AnonpheneOS privacy settings:
 * the every-app list, or the sending app's own list. The sender is taken from the uid that
 * opened the sheet, not from what the sending app says about itself.
 */
public final class AnonShareFilter {
    private AnonShareFilter() {}

    /**
     * True when the share list doesn't allow {@code target} for the app that opened the sheet
     * ({@code launchedFromUid}). Called once per target while the sheet builds its list.
     */
    public static boolean blocks(Context ctx, int launchedFromUid, ComponentName target) {
        if (target == null) return false;
        String text = Settings.Secure.getStringForUser(ctx.getContentResolver(), ShareList.SETTING,
                UserHandle.getUserId(launchedFromUid));
        if (text == null || text.isEmpty()) return false;
        ShareList list = ShareList.parse(text);
        String source = senderOf(ctx.getPackageManager(), launchedFromUid);
        return !list.allows(source, target.getPackageName(), target.getClassName());
    }

    /**
     * The sending app's package. A uid shared by several packages (system ones) gives no single
     * sender, so the every-app list applies.
     */
    private static String senderOf(PackageManager pm, int uid) {
        String[] pkgs = pm.getPackagesForUid(uid);
        return pkgs != null && pkgs.length == 1 ? pkgs[0] : null;
    }
}
