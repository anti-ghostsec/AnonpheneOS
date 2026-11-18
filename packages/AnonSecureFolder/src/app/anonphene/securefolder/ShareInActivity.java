/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.widget.Toast;

/**
 * "Into Secure Folder": a share target on the main side that saves files into the folder's
 * Downloads. A locked folder is opened first; share again once it's unlocked.
 */
public class ShareInActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UserHandle folder = Folder.find(this);
        if (folder == null) {
            Toast.makeText(this, R.string.no_folder, Toast.LENGTH_SHORT).show();
        } else if (!Folder.isInside(this) && Forwarder.hasSomething(getIntent())) {
            if (Folder.isLocked(this, folder)) {
                Toast.makeText(this, R.string.unlock_first, Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, FolderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Forwarder.forward(this, getIntent(), folder);
            }
        }
        finish();
    }
}
