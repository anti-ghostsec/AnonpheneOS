/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.privacy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the privacy defaults back at every boot; empties the trash once storage is unlocked. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) {
            Store.applyDefaults(ctx);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            Store.applyDefaults(ctx);
            PendingResult r = goAsync();
            new Thread(() -> {
                try {
                    Cleaner.emptyTrash(ctx);
                } finally {
                    r.finish();
                }
            }).start();
        }
    }
}
