/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;

import app.anonphene.folderkey.FolderKey;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Secure Folder is Android's private profile (the one Android calls Private Space), kept
 * hidden: the launcher never shows it, not as a tab and not as a section, and it locks with the
 * phone. Its apps are listed only inside {@link FolderActivity}.
 *
 * <p>Its lock is not a PIN typed into Android's prompt: it's a 256-bit credential made by
 * {@link FolderKey} from a passphrase of 12 to 256 characters and any number of keyfiles, and
 * handed to Android directly. Android then binds it to the phone's secure hardware as for any
 * lock. Android's own prompt can't be used to guess at it.
 *
 * <p>Locking stops the profile, which evicts its file-encryption key from memory. Deleting it
 * removes the user, and vold erases the key from the secure hardware, so its files can't be
 * decrypted again even from a copy of the flash.
 */
final class Folder {
    // Settings.Secure keys from AOSP (hidden there, so spelled out here).
    static final String HIDE_ENTRY_POINT = "hide_privatespace_entry_point";
    static final String AUTO_LOCK = "private_space_auto_lock";
    static final int AUTO_LOCK_ON_DEVICE_LOCK = 0;

    private static final String PREFS = "folder";
    private static final String KEY_CODE_SALT = "code_salt";
    private static final String KEY_CODE_HASH = "code_hash";
    private static final String KEY_PARAMS = "key_params";
    private static final String KEY_WRONG = "wrong_tries";
    private static final String KEY_WIPE_AFTER = "wipe_after";
    /** Choices for "delete the folder after N wrong passphrases"; 0 is off. */
    static final int[] WIPE_CHOICES = {0, 3, 5, 10, 20};

    /** What happened when unlocking. */
    enum Unlock { OK, WRONG, WIPED, THROTTLED, NO_FOLDER, UNSUPPORTED }

    private Folder() {}

    /** The hidden profile of the current user, or null when there isn't one. */
    static UserHandle find(Context ctx) {
        UserManager um = ctx.getSystemService(UserManager.class);
        for (UserHandle h : um.getUserProfiles()) {
            if (isPrivate(ctx, h)) return h;
        }
        return null;
    }

    private static boolean isPrivate(Context ctx, UserHandle h) {
        UserManager um = ctx.createContextAsUser(h, 0).getSystemService(UserManager.class);
        return um.isPrivateProfile();
    }

    /** True when running inside the folder itself. */
    static boolean isInside(Context ctx) {
        return ctx.getSystemService(UserManager.class).isPrivateProfile();
    }

    /** The user that owns the folder, seen from inside it. */
    static UserHandle parent(Context ctx) {
        UserManager um = ctx.getSystemService(UserManager.class);
        UserHandle p = um.getProfileParent(Process.myUserHandle());
        return p != null ? p : UserHandle.SYSTEM;
    }

    static boolean isLocked(Context ctx, UserHandle folder) {
        return ctx.getSystemService(UserManager.class).isQuietModeEnabled(folder);
    }

    /**
     * Makes the folder: a private profile with its own lock set to {@code credential} (from
     * {@link FolderKey#credential}), hidden from the launcher, locking with the phone. A Private
     * Space made earlier in Settings is refused rather than taken over, since its lock is a
     * normal one. Runs off the main thread.
     */
    static UserHandle create(Context ctx, FolderKey.Params params, byte[] credential)
            throws UserManager.UserOperationException {
        if (find(ctx) != null) throw new IllegalStateException(ctx.getString(R.string.existing_space));
        UserManager um = ctx.getSystemService(UserManager.class);
        UserHandle folder = um.createProfile(
                ctx.getString(R.string.app_name), UserManager.USER_TYPE_PROFILE_PRIVATE, new ArraySet<>());
        if (folder == null) throw new IllegalStateException("Android refused to make the profile");
        boolean ok = false;
        LockscreenCredential cred = toCredential(credential);
        try {
            ctx.getSystemService(ActivityManager.class).startProfile(folder);
            // Secure Folder runs inside the folder too, for "Outside Secure Folder". The system
            // image allowlists it for private profiles; this also covers a copy updated in /data.
            try {
                // Seen through the "android" package, which every user has: until Secure Folder
                // is in the profile, Android won't make a context for it there.
                ctx.createPackageContextAsUser("android", 0, folder).getPackageManager()
                        .installExistingPackage(ctx.getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalStateException("Secure Folder couldn't be put in the folder", e);
            }
            LockPatternUtils lpu = new LockPatternUtils(ctx);
            lpu.setSeparateProfileChallengeEnabled(folder.getIdentifier(), true, null);
            if (!lpu.setLockCredential(cred, LockscreenCredential.createNone(), folder.getIdentifier())) {
                throw new IllegalStateException("Android refused the folder's lock");
            }
            prefs(ctx).edit().putString(KEY_PARAMS, params.serialize()).putInt(KEY_WRONG, 0).commit();
            applyDefaults(ctx);
            setShareTargets(ctx, folder, true);
            ok = true;
        } finally {
            cred.zeroize();
            if (!ok) um.removeUser(folder);
        }
        lock(ctx, folder);
        return folder;
    }

    /** The folder's Argon2id settings and salt, or null when it wasn't made here. */
    static FolderKey.Params params(Context ctx) {
        String s = prefs(ctx).getString(KEY_PARAMS, null);
        try {
            return s == null ? null : FolderKey.Params.parse(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Unlocks with a credential from {@link FolderKey#credential}. The profile is started without
     * Android's prompt, and its storage opens only if the credential is right. Too many wrong
     * ones delete the folder when that's switched on. Runs off the main thread.
     */
    static Unlock unlock(Context ctx, byte[] credential) {
        UserHandle folder = find(ctx);
        if (folder == null) return Unlock.NO_FOLDER;
        UserManager um = ctx.getSystemService(UserManager.class);
        // Counted as wrong before checking, and cleared only when right, so stopping the app
        // mid-check can't save a guess from the count.
        int wrong = prefs(ctx).getInt(KEY_WRONG, 0) + 1;
        prefs(ctx).edit().putInt(KEY_WRONG, wrong).commit();
        LockscreenCredential cred = toCredential(credential);
        try {
            // The profile starts with its storage still locked (Android's prompt is skipped
            // for Secure Folder only, by AnonpheneOS's UserManagerService gate); the folder's
            // credential then opens it. A wrong one stops it again.
            try {
                startWithoutPrompt(um, folder);
            } catch (IllegalArgumentException | SecurityException e) {
                return Unlock.UNSUPPORTED;
            }
            boolean right;
            try {
                right = new LockPatternUtils(ctx).checkCredential(cred, folder.getIdentifier(), null);
            } catch (LockPatternUtils.RequestThrottledException e) {
                um.requestQuietModeEnabled(true, folder);
                return Unlock.THROTTLED;
            }
            if (!right) {
                um.requestQuietModeEnabled(true, folder);
                int limit = wipeAfter(ctx);
                if (limit > 0 && wrong >= limit) {
                    delete(ctx, folder);
                    return Unlock.WIPED;
                }
                return Unlock.WRONG;
            }
            prefs(ctx).edit().putInt(KEY_WRONG, 0).commit();
            return Unlock.OK;
        } finally {
            cred.zeroize();
        }
    }

    /** Changes the passphrase or keyfiles; the folder must be unlocked. */
    static boolean changeKey(Context ctx, FolderKey.Params newParams, byte[] oldCred, byte[] newCred) {
        UserHandle folder = find(ctx);
        if (folder == null) return false;
        LockscreenCredential o = toCredential(oldCred), n = toCredential(newCred);
        try {
            boolean ok = new LockPatternUtils(ctx).setLockCredential(n, o, folder.getIdentifier());
            if (ok) prefs(ctx).edit().putString(KEY_PARAMS, newParams.serialize()).commit();
            return ok;
        } finally {
            o.zeroize();
            n.zeroize();
        }
    }

    static int wipeAfter(Context ctx) {
        return prefs(ctx).getInt(KEY_WIPE_AFTER, 0);
    }

    static void setWipeAfter(Context ctx, int n) {
        prefs(ctx).edit().putInt(KEY_WIPE_AFTER, n).commit();
    }

    /**
     * Starts the profile without Android's credential prompt. Plain Android refuses this for
     * private profiles; AnonpheneOS's UserManagerService allows it for Secure Folder only. The
     * flag and the 4-argument call are Android-internal, so they're looked up by name.
     */
    private static void startWithoutPrompt(UserManager um, UserHandle folder) {
        try {
            int flag = UserManager.class.getField("QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL").getInt(null);
            UserManager.class.getMethod("requestQuietModeEnabled", boolean.class, UserHandle.class,
                    android.content.IntentSender.class, int.class).invoke(um, false, folder, null, flag);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static LockscreenCredential toCredential(byte[] hex) {
        char[] chars = new char[hex.length];
        for (int i = 0; i < hex.length; i++) chars[i] = (char) hex[i];
        try {
            return LockscreenCredential.createPassword(CharBuffer.wrap(chars));
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /** Hidden from the launcher; locks every time the phone locks. Safe to call again. */
    static void applyDefaults(Context ctx) {
        Settings.Secure.putInt(ctx.getContentResolver(), HIDE_ENTRY_POINT, 1);
        Settings.Secure.putInt(ctx.getContentResolver(), AUTO_LOCK, AUTO_LOCK_ON_DEVICE_LOCK);
    }

    static void lock(Context ctx, UserHandle folder) {
        ctx.getSystemService(UserManager.class).requestQuietModeEnabled(true, folder);
    }

    /** Deletes the folder, its apps and data, and its key. */
    static boolean delete(Context ctx, UserHandle folder) {
        setShareTargets(ctx, folder, false);
        prefs(ctx).edit().remove(KEY_PARAMS).remove(KEY_WRONG).commit();
        return ctx.getSystemService(UserManager.class).removeUser(folder);
    }

    /** The folder's apps, by name. */
    static List<LauncherActivityInfo> apps(Context ctx, UserHandle folder) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        List<LauncherActivityInfo> out = new ArrayList<>(la.getActivityList(null, folder));
        out.removeIf(a -> a.getComponentName().getPackageName().equals(ctx.getPackageName()));
        out.sort(Comparator.comparing(a -> a.getLabel().toString().toLowerCase()));
        return out;
    }

    /** Apps on the main side that aren't in the folder yet. */
    static List<LauncherActivityInfo> addable(Context ctx, UserHandle folder) {
        LauncherApps la = ctx.getSystemService(LauncherApps.class);
        Set<String> inside = new HashSet<>();
        for (LauncherActivityInfo a : la.getActivityList(null, folder)) {
            inside.add(a.getComponentName().getPackageName());
        }
        List<LauncherActivityInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LauncherActivityInfo a : la.getActivityList(null, UserHandle.of(UserHandle.myUserId()))) {
            String pkg = a.getComponentName().getPackageName();
            if (pkg.equals(ctx.getPackageName()) || inside.contains(pkg) || !seen.add(pkg)) continue;
            out.add(a);
        }
        out.sort(Comparator.comparing(a -> a.getLabel().toString().toLowerCase()));
        return out;
    }

    /** Puts an app that's installed on the main side into the folder too (its own fresh data). */
    static void add(Context ctx, UserHandle folder, String pkg) throws PackageManager.NameNotFoundException {
        ctx.createContextAsUser(folder, 0).getPackageManager().installExistingPackage(pkg);
    }

    static void launch(Context ctx, UserHandle folder, ComponentName c) {
        ctx.getSystemService(LauncherApps.class).startMainActivity(c, folder, null, null);
    }

    /** "Outside Secure Folder" is offered inside the folder, "Into Secure Folder" outside. */
    static void setShareTargets(Context ctx, UserHandle folder, boolean exists) {
        int on = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager mainPm = ctx.getPackageManager();
        mainPm.setComponentEnabledSetting(new ComponentName(ctx, ShareInActivity.class),
                exists ? on : off, PackageManager.DONT_KILL_APP);
        mainPm.setComponentEnabledSetting(new ComponentName(ctx, ShareOutActivity.class),
                off, PackageManager.DONT_KILL_APP);
        if (exists) {
            PackageManager inPm = ctx.createContextAsUser(folder, 0).getPackageManager();
            inPm.setComponentEnabledSetting(new ComponentName(ctx, ShareOutActivity.class),
                    on, PackageManager.DONT_KILL_APP);
            inPm.setComponentEnabledSetting(new ComponentName(ctx, ShareInActivity.class),
                    off, PackageManager.DONT_KILL_APP);
        }
    }

    // ---- Ways in ----

    enum Way { ICON, DISGUISE, TILE }

    static ComponentName componentOf(Context ctx, Way way) {
        switch (way) {
            case ICON: return new ComponentName(ctx.getPackageName(), ctx.getPackageName() + ".Icon");
            case DISGUISE: return new ComponentName(ctx.getPackageName(), ctx.getPackageName() + ".Disguise");
            default: return new ComponentName(ctx, FolderTile.class);
        }
    }

    static boolean isOn(Context ctx, Way way) {
        int s = ctx.getPackageManager().getComponentEnabledSetting(componentOf(ctx, way));
        return s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static void set(Context ctx, Way way, boolean on) {
        ctx.getPackageManager().setComponentEnabledSetting(componentOf(ctx, way),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    // ---- Dialer code: kept as a salted hash, compared in constant time ----

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isValidCode(String code) {
        return code != null && code.matches("[0-9]{4,16}");
    }

    /** Sets the code, or turns the dialer way in off when {@code code} is null. */
    static void setCode(Context ctx, String code) {
        if (code == null) {
            prefs(ctx).edit().putString(KEY_CODE_HASH, "off").remove(KEY_CODE_SALT).commit();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs(ctx).edit()
                .putString(KEY_CODE_SALT, hex(salt))
                .putString(KEY_CODE_HASH, hex(hash(salt, code)))
                .commit();
    }

    static boolean hasCode(Context ctx) {
        String stored = prefs(ctx).getString(KEY_CODE_HASH, null);
        return stored != null && !"off".equals(stored);
    }

    static boolean codeMatches(Context ctx, String code) {
        SharedPreferences p = prefs(ctx);
        String stored = p.getString(KEY_CODE_HASH, null);
        if ("off".equals(stored) || !isValidCode(code)) return false;
        // No code until you choose one when the folder is made: no default anyone could look up.
        if (stored == null) return false;
        byte[] salt = unhex(p.getString(KEY_CODE_SALT, ""));
        return MessageDigest.isEqual(hash(salt, code), unhex(stored));
    }

    private static byte[] hash(byte[] salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = code.getBytes(StandardCharsets.UTF_8);
            // A code is short, so the hash only keeps it out of plain sight in the app's own
            // files; the folder's lock is what protects what's inside.
            for (int i = 0; i < 100_000; i++) {
                md.update(salt);
                h = md.digest(h);
            }
            return h;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
