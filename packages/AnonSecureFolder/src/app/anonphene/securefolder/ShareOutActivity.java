/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.Activity;
import android.os.Bundle;

/**
 * "Outside Secure Folder": a share target inside the folder that saves files into the main
 * side's Downloads. Enabled only inside the folder.
 */
public class ShareOutActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Folder.isInside(this) && Forwarder.hasSomething(getIntent())) {
            Forwarder.forward(this, getIntent(), Folder.parent(this));
        }
        finish();
    }
}
