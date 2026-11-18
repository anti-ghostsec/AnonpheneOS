/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A plain "Tools" tile that opens the folder. Off until switched on under Ways in. */
public class FolderTile extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, FolderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Opens after the phone is unlocked; the tile never shows the folder over the lock screen.
        unlockAndRun(() -> startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)));
    }
}
