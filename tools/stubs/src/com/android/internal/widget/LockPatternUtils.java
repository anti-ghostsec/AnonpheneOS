// Compile-only stub of an Android-internal class: only the members AnonpheneOS uses, with the
// real signatures. Never packaged; the real class on the phone is used at run time.
package com.android.internal.widget;

public class LockPatternUtils {
    public LockPatternUtils(android.content.Context context) { throw new RuntimeException("stub"); }
    public boolean setLockCredential(LockscreenCredential newCredential, LockscreenCredential savedCredential, int userHandle) { throw new RuntimeException("stub"); }
    public void setSeparateProfileChallengeEnabled(int userHandle, boolean enabled, LockscreenCredential profilePassword) { throw new RuntimeException("stub"); }
    public VerifyCredentialResponse checkCredential(LockscreenCredential credential, int userId, CheckCredentialProgressCallback progressCallback) { throw new RuntimeException("stub"); }

    public interface CheckCredentialProgressCallback {
        void onEarlyMatched();
    }
}
