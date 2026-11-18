/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Dialing *#*#code#*#* with your own code opens the folder. Any other code does nothing, so the
 * dialer gives away nothing about whether a folder exists.
 */
public class SecretCodeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Uri data = intent.getData();
        if (data == null || Folder.isInside(ctx)) return;
        String code = data.getHost();
        if (!Folder.codeMatches(ctx, code)) return;
        ctx.startActivity(new Intent(ctx, FolderActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
