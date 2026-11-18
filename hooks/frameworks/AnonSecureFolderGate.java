/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 *
 * Copied into frameworks/base/services/core/java/com/android/server/pm by scripts/apply.py.
 */
package com.android.server.pm;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

/**
 * Decides whether a caller may start a private profile without Android's credential prompt.
 * Only AnonpheneOS Secure Folder may, when it's signed with the platform key, runs as the parent
 * of that profile, and the profile really is a private one. It then checks the folder's
 * credential itself; until that's right, the profile runs with its storage still locked.
 */
final class AnonSecureFolderGate {
    static final String PACKAGE = "app.anonphene.securefolder";

    private AnonSecureFolderGate() {}

    static boolean allows(Context ctx, String callingPackage, int callingUid, int userId) {
        if (!PACKAGE.equals(callingPackage)) return false;
        PackageManager pm = ctx.getPackageManager();
        try {
            int callerUser = UserHandle.getUserId(callingUid);
            if (pm.getPackageUidAsUser(PACKAGE, callerUser) != callingUid) return false;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        if (pm.checkSignatures("android", PACKAGE) != PackageManager.SIGNATURE_MATCH) return false;
        UserManager um = ctx.getSystemService(UserManager.class);
        UserInfo profile = um.getUserInfo(userId);
        UserHandle parent = um.getProfileParent(UserHandle.of(userId));
        return profile != null && profile.isPrivateProfile()
                && parent != null && parent.getIdentifier() == UserHandle.getUserId(callingUid);
    }
}
