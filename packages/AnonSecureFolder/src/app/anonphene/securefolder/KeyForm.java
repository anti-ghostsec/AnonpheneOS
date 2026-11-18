/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.anonphene.folderkey.FolderKey;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Secure Folder's own lock screen: a passphrase of 12 to 256 characters and any number of
 * keyfiles (a photo, a document, a file on a USB stick: anything that doesn't change). Keyfiles
 * are read once and only their BLAKE2b-512 digests are kept, in memory, until the form closes;
 * which files they were is never written down.
 */
final class KeyForm {
    enum Mode { CREATE, UNLOCK, CHANGE }

    interface Submit {
        /** Called off the main thread. Wipe nothing here: the form wipes both after it returns. */
        void run(char[] passphrase, List<byte[]> keyfiles);
    }

    static final int PICK_KEYFILES = 41;

    private final Activity mActivity;
    private final Mode mMode;
    private final Submit mSubmit;
    private final List<byte[]> mDigests = new ArrayList<>();
    private final List<String> mNames = new ArrayList<>();
    private final EditText mPass, mConfirm;
    private final TextView mKeyfiles, mStatus;
    private final Button mGo, mAdd, mClear;
    final View view;

    KeyForm(Activity a, Mode mode, Submit submit) {
        mActivity = a;
        mMode = mode;
        mSubmit = submit;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = text(mode == Mode.UNLOCK ? R.string.key_title_unlock
                : mode == Mode.CREATE ? R.string.key_title_create : R.string.key_title_change, 26, true);
        col.addView(title);
        if (mode != Mode.UNLOCK) col.addView(text(R.string.key_create_body, 15, false), gap(8));

        mPass = passwordField(R.string.key_passphrase);
        col.addView(mPass, gap(24));
        mConfirm = passwordField(R.string.key_confirm);
        if (mode != Mode.UNLOCK) col.addView(mConfirm, gap(12));
        CheckBox show = new CheckBox(a);
        show.setText(R.string.key_show);
        show.setOnCheckedChangeListener((b, on) -> {
            int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD);
            mPass.setInputType(type);
            mConfirm.setInputType(type);
        });
        col.addView(show, gap(8));

        col.addView(text(R.string.key_keyfiles_head, 14, false), gap(20));
        mKeyfiles = text(R.string.key_no_keyfiles, 16, true);
        col.addView(mKeyfiles, gap(4));
        LinearLayout row = new LinearLayout(a);
        mAdd = new Button(a, null, 0, R.style.Anon_Button_Secondary);
        mAdd.setText(R.string.key_add_keyfiles);
        mAdd.setOnClickListener(v -> a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_KEYFILES));
        mClear = new Button(a, null, 0, R.style.Anon_Button_Secondary);
        mClear.setText(R.string.key_clear_keyfiles);
        mClear.setOnClickListener(v -> clearKeyfiles());
        row.addView(mAdd);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clearLp.setMarginStart(dp(12));
        row.addView(mClear, clearLp);
        col.addView(row, gap(12));

        mGo = new Button(a);
        mGo.setText(mode == Mode.UNLOCK ? R.string.unlock : R.string.key_set);
        mGo.setOnClickListener(v -> go());
        col.addView(mGo, gap(32));
        mStatus = text(0, 15, false);
        mStatus.setGravity(Gravity.CENTER);
        col.addView(mStatus, gap(16));

        ScrollView scroll = new ScrollView(a);
        scroll.addView(col);
        view = scroll;
        updateKeyfiles();
    }

    /** From the activity's onActivityResult. */
    void onPicked(Intent data) {
        if (data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        busy(true, R.string.key_reading);
        ContentResolver cr = mActivity.getContentResolver();
        new Thread(() -> {
            List<byte[]> digests = new ArrayList<>();
            List<String> names = new ArrayList<>();
            boolean failed = false;
            for (Uri u : uris) {
                try (InputStream in = cr.openInputStream(u)) {
                    if (in == null) throw new java.io.IOException();
                    digests.add(FolderKey.keyfileDigest(in));
                    names.add(nameOf(cr, u));
                } catch (Exception e) {
                    failed = true;
                }
            }
            boolean f = failed;
            mActivity.runOnUiThread(() -> {
                if (mDigests.size() + digests.size() > FolderKey.MAX_KEYFILES) {
                    status(R.string.key_too_many);
                } else {
                    mDigests.addAll(digests);
                    mNames.addAll(names);
                    status(f ? R.string.key_read_failed : 0);
                }
                busy(false, 0);
                updateKeyfiles();
            });
        }).start();
    }

    private void go() {
        Editable e = mPass.getText();
        if (mMode != Mode.UNLOCK) {
            String why = FolderKey.checkPassphrase(e);
            if (why != null) {
                mPass.setError(why);
                return;
            }
            if (!e.toString().contentEquals(mConfirm.getText())) {
                mConfirm.setError(mActivity.getString(R.string.key_mismatch));
                return;
            }
        } else if (e.length() == 0) {
            return;
        }
        char[] pass = new char[e.length()];
        e.getChars(0, e.length(), pass, 0);
        mPass.getText().clear();
        mConfirm.getText().clear();
        List<byte[]> digests = new ArrayList<>(mDigests);
        busy(true, mMode == Mode.UNLOCK ? R.string.key_working_unlock : R.string.key_working_create);
        new Thread(() -> {
            try {
                mSubmit.run(pass, digests);
            } catch (RuntimeException err) {
                String why = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
                mActivity.runOnUiThread(() -> status(why));
            } finally {
                Arrays.fill(pass, '\0');
                mActivity.runOnUiThread(() -> busy(false, -1));
            }
        }).start();
    }

    /** Shows a result line; 0 clears it. */
    void status(int res) {
        mStatus.setText(res == 0 ? "" : mActivity.getString(res));
    }

    void status(CharSequence s) {
        mStatus.setText(s);
    }

    /** Forgets the keyfile digests, e.g. when the screen goes away. */
    void clearKeyfiles() {
        for (byte[] d : mDigests) Arrays.fill(d, (byte) 0);
        mDigests.clear();
        mNames.clear();
        updateKeyfiles();
    }

    private void updateKeyfiles() {
        if (mNames.isEmpty()) {
            mKeyfiles.setText(R.string.key_no_keyfiles);
        } else {
            mKeyfiles.setText(mActivity.getResources().getQuantityString(
                    R.plurals.key_n_keyfiles, mNames.size(), mNames.size(), String.join(", ", mNames)));
        }
        mClear.setEnabled(!mNames.isEmpty());
    }

    private void busy(boolean on, int res) {
        mGo.setEnabled(!on);
        mAdd.setEnabled(!on);
        mClear.setEnabled(!on && !mNames.isEmpty());
        mPass.setEnabled(!on);
        mConfirm.setEnabled(!on);
        if (res > 0) status(res);
    }

    private EditText passwordField(int hint) {
        EditText e = new EditText(mActivity);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Keyboards are asked not to learn or remember what's typed here.
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(FolderKey.MAX_PASSPHRASE * 2)});
        e.setSaveEnabled(false);
        return e;
    }

    private TextView text(int res, int sp, boolean strong) {
        TextView t = new TextView(mActivity);
        if (res != 0) t.setText(res);
        t.setTextSize(sp);
        t.setTextColor(mActivity.getColor(strong ? R.color.anon_text : R.color.anon_muted));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** Full width, with {@code topDp} of space above. */
    private LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private static String nameOf(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // no name given
        }
        return "?";
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}
