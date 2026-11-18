/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;

import java.util.ArrayList;

/**
 * Hands what was shared to {@link ReceiveActivity} in the other user (the folder or the main
 * side). Only read access to exactly the shared items goes across, and only for that one hand-off.
 */
final class Forwarder {
    static final String EXTRA_ITEMS = "app.anonphene.securefolder.ITEMS";

    private Forwarder() {}

    static boolean hasSomething(Intent send) {
        return !streams(send).isEmpty() || send.getCharSequenceExtra(Intent.EXTRA_TEXT) != null;
    }

    static void forward(Activity from, Intent send, UserHandle to) {
        ArrayList<Uri> items = new ArrayList<>();
        int me = android.os.Process.myUserHandle().getIdentifier();
        for (Uri u : streams(send)) {
            // content://<user>@authority/..., so the other user reads it from this one.
            String auth = u.getEncodedAuthority();
            items.add(auth == null || auth.contains("@") ? u
                    : u.buildUpon().encodedAuthority(me + "@" + auth).build());
        }
        Intent receive = new Intent(from, ReceiveActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putParcelableArrayListExtra(EXTRA_ITEMS, items);
        CharSequence text = send.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) receive.putExtra(Intent.EXTRA_TEXT, text);
        if (!items.isEmpty()) {
            ClipData clip = ClipData.newRawUri(null, items.get(0));
            for (int i = 1; i < items.size(); i++) clip.addItem(new ClipData.Item(items.get(i)));
            receive.setClipData(clip);
        }
        try {
            from.startActivityAsUser(receive, to);
        } catch (SecurityException e) {
            // The sending app didn't give access to what it shared, so it can't be passed on.
            android.widget.Toast.makeText(from, R.string.send_no_access, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> streams(Intent send) {
        ArrayList<Uri> out = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(send.getAction())) {
            ArrayList<Uri> list = send.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null && "content".equals(u.getScheme())) out.add(u);
        } else {
            Uri u = send.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null && "content".equals(u.getScheme())) out.add(u);
        }
        return out;
    }
}
