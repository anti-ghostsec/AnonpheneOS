/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Runs in the receiving user and saves each item into Downloads/Secure Folder (inside the folder)
 * or Downloads/From Secure Folder (outside). Copies are made straight from the sender's stream:
 * nothing is staged in a cache on either side.
 */
public class ReceiveActivity extends Activity {
    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ArrayList<Uri> items = getIntent().getParcelableArrayListExtra(Forwarder.EXTRA_ITEMS);
        CharSequence text = getIntent().getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
        String folder = "Download/" + (Folder.isInside(this) ? "Secure Folder" : "From Secure Folder");
        ContentResolver cr = getContentResolver();
        new Thread(() -> {
            int saved = 0;
            String error = null;
            try {
                if (items != null) {
                    for (Uri u : items) {
                        try (InputStream in = cr.openInputStream(u)) {
                            if (in != null && save(cr, folder, nameOf(cr, u), cr.getType(u), in)) saved++;
                        }
                    }
                }
                if (text != null && (items == null || items.isEmpty())) {
                    try (InputStream in = new java.io.ByteArrayInputStream(
                            text.toString().getBytes(StandardCharsets.UTF_8))) {
                        if (save(cr, folder, "Shared text.txt", "text/plain", in)) saved++;
                    }
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            }
            final int n = saved;
            final String err = error;
            runOnUiThread(() -> {
                Toast.makeText(this, err == null ? getString(R.string.sent, n)
                        : getString(R.string.send_failed, err), Toast.LENGTH_LONG).show();
                finish();
            });
        }).start();
    }

    private static boolean save(ContentResolver cr, String folder, String name, String type, InputStream in)
            throws java.io.IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, type != null ? type : "application/octet-stream");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri out = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (out == null) return false;
        boolean ok = false;
        try (OutputStream os = cr.openOutputStream(out, "w")) {
            if (os == null) return false;
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) os.write(buf, 0, r);
            ok = true;
        } finally {
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(out, done, null, null);
            } else {
                cr.delete(out, null, null);
            }
        }
        return true;
    }

    private static String nameOf(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                String n = c.getString(0).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
                if (!n.isEmpty() && !n.startsWith(".")) return n.length() > 120 ? n.substring(0, 120) : n;
            }
        } catch (RuntimeException ignored) {
            // the sender gives no name
        }
        return "Shared file";
    }
}
