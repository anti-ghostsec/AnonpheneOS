/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.privacy;

import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.storage.IStorageManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;

/**
 * Clearing traces and shredding files.
 *
 * <p>Phones store data on flash, and the flash controller moves blocks around on its own, so
 * overwriting a file can't promise that no old copy is left in a block no app can reach. What
 * makes deleted data unreadable on AnonpheneOS is encryption: every file has its own key, derived
 * from the user's key, and deleting the file drops what's needed to rebuild it. Shredding adds an
 * overwrite and a trim on top, so the old blocks are released to the controller to erase.
 */
final class Cleaner {
    private Cleaner() {}

    /** Empties the clipboard and the photo and file trash, then trims free space. */
    static void clearTraces(Context ctx) {
        ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
        if (cm != null) cm.clearPrimaryClip();
        emptyTrash(ctx);
        trim();
    }

    /** Deletes everything in MediaStore's trash now, instead of after 30 days. */
    static int emptyTrash(Context ctx) {
        Bundle q = new Bundle();
        q.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        try {
            return ctx.getContentResolver().delete(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), q);
        } catch (RuntimeException e) {
            return 0; // storage not ready yet (before the first unlock)
        }
    }

    /** Overwrites each file with random bytes, deletes it, and trims. Returns how many went. */
    static int shred(Context ctx, List<Uri> files) {
        ContentResolver cr = ctx.getContentResolver();
        SecureRandom rnd = new SecureRandom();
        int done = 0;
        for (Uri u : files) {
            try {
                long size = sizeOf(cr, u);
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "rw")) {
                    if (pfd != null) {
                        if (size < 0) size = pfd.getStatSize();
                        overwrite(pfd, size, rnd);
                    }
                }
                if (DocumentsContract.deleteDocument(cr, u)) done++;
            } catch (IOException | RuntimeException e) {
                // left as it was; counted as not shredded
            }
        }
        trim();
        return done;
    }

    private static void overwrite(ParcelFileDescriptor pfd, long size, SecureRandom rnd) throws IOException {
        byte[] buf = new byte[256 * 1024];
        try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.getChannel().position(0);
            long left = size;
            while (left > 0) {
                int n = (int) Math.min(buf.length, left);
                rnd.nextBytes(buf);
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
            out.getFD().sync();
        }
    }

    private static long sizeOf(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
            // the provider gives no size; the file's own size is used
        }
        return -1;
    }

    /** Asks vold to trim free space now, so freed blocks are erased by the flash controller. */
    static void trim() {
        try {
            IStorageManager sm = IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));
            if (sm != null) sm.runMaintenance();
        } catch (Exception ignored) {
            // the next idle maintenance trims anyway
        }
    }
}
