/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.securefolder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.anonphene.folderkey.FolderKey;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Secure Folder's own screen: one grid of the folder's apps. The main launcher never lists
 * them (no tab, no section), so this is the only place they appear.
 */
public class FolderActivity extends Activity {
    private static final int MENU_ADD = 1, MENU_LOCK = 2, MENU_WAYS = 3, MENU_DELETE = 4,
            MENU_KEY = 5, MENU_WIPE = 6;

    private UserHandle mFolder;
    private final BroadcastReceiver mProfileChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refresh();
        }
    };

    @Override
    public void setContentView(View v) {
        super.setContentView(Bars.fit(this, v));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // No screenshots, no recents thumbnail.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Folder.isInside(this)) {
            finish();
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PROFILE_AVAILABLE);
        f.addAction(Intent.ACTION_PROFILE_UNAVAILABLE);
        f.addAction(Intent.ACTION_PROFILE_REMOVED);
        registerReceiver(mProfileChanged, f, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(mProfileChanged);
        } catch (IllegalArgumentException ignored) {
            // finished before it was registered
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private enum State { NONE, LOCKED, OPEN }

    private State mState;
    private KeyForm mForm;

    private void refresh() {
        mFolder = Folder.find(this);
        State st = mFolder == null ? State.NONE : Folder.isLocked(this, mFolder) ? State.LOCKED : State.OPEN;
        invalidateOptionsMenu();
        // A broadcast mid-typing doesn't rebuild the form under your fingers.
        if (st == mState && st != State.OPEN) return;
        mState = st;
        closeForm();
        if (st == State.NONE) {
            setContentView(new View(this));
            askCreate();
        } else if (st == State.LOCKED) {
            showLocked();
        } else {
            showGrid();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Keyfile digests and a half-typed passphrase don't outlive the screen.
        if (mForm != null) {
            mForm.clearKeyfiles();
            mState = null;
        }
    }

    private void closeForm() {
        if (mForm != null) mForm.clearKeyfiles();
        mForm = null;
    }

    private void showForm(KeyForm f) {
        closeForm();
        mForm = f;
        setContentView(f.view);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == KeyForm.PICK_KEYFILES && result == RESULT_OK && mForm != null) mForm.onPicked(data);
    }

    // ---- Making it ----

    private void askCreate() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.create_title)
                .setMessage(R.string.create_body)
                .setPositiveButton(R.string.create, (d, w) -> showForm(new KeyForm(this, KeyForm.Mode.CREATE,
                        this::create)))
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    /** Off the main thread, from the form. */
    private void create(char[] pass, List<byte[]> keyfiles) {
        FolderKey.Params params = FolderKey.Params.strong(new SecureRandom());
        byte[] cred = FolderKey.credential(pass, keyfiles, params);
        try {
            mFolder = Folder.create(this, params, cred);
            runOnUiThread(() -> {
                mState = null;
                refresh();
                askCode(true);
            });
        } catch (Exception e) {
            runOnUiThread(() -> mForm.status(getString(R.string.create_failed, e.getMessage())));
        } finally {
            Arrays.fill(cred, (byte) 0);
        }
    }

    // ---- Locked ----

    private void showLocked() {
        showForm(new KeyForm(this, KeyForm.Mode.UNLOCK, this::unlock));
    }

    /** Off the main thread, from the form. */
    private void unlock(char[] pass, List<byte[]> keyfiles) {
        FolderKey.Params params = Folder.params(this);
        if (params == null) {
            runOnUiThread(() -> mForm.status(R.string.key_no_params));
            return;
        }
        byte[] cred = FolderKey.credential(pass, keyfiles, params);
        Folder.Unlock result;
        try {
            result = Folder.unlock(this, cred);
        } finally {
            Arrays.fill(cred, (byte) 0);
        }
        runOnUiThread(() -> {
            switch (result) {
                case OK: mState = null; refresh(); break;
                // Wrong passphrase and wrong keyfiles look the same: nothing says which was wrong.
                case WRONG: mForm.status(R.string.key_wrong); break;
                case THROTTLED: mForm.status(R.string.key_throttled); break;
                case UNSUPPORTED: mForm.status(R.string.key_unsupported); break;
                case WIPED: case NO_FOLDER: finish(); break;
            }
        });
    }

    private void changeKey() {
        byte[][] old = new byte[1][];
        showForm(new KeyForm(this, KeyForm.Mode.UNLOCK, (pass, keyfiles) -> {
            FolderKey.Params params = Folder.params(this);
            if (params == null) return;
            old[0] = FolderKey.credential(pass, keyfiles, params);
            runOnUiThread(() -> showForm(new KeyForm(this, KeyForm.Mode.CHANGE, (p2, k2) -> {
                FolderKey.Params fresh = FolderKey.Params.strong(new SecureRandom());
                byte[] cred = FolderKey.credential(p2, k2, fresh);
                boolean ok;
                try {
                    ok = Folder.changeKey(this, fresh, old[0], cred);
                } finally {
                    Arrays.fill(cred, (byte) 0);
                    Arrays.fill(old[0], (byte) 0);
                }
                runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(this, R.string.key_changed, Toast.LENGTH_LONG).show();
                        mState = null;
                        refresh();
                    } else {
                        // The old passphrase or keyfiles weren't right: start again from the top.
                        Toast.makeText(this, R.string.key_wrong, Toast.LENGTH_LONG).show();
                        changeKey();
                    }
                });
            })));
        }));
    }

    private void wipeLimit() {
        int[] choices = Folder.WIPE_CHOICES;
        CharSequence[] labels = new CharSequence[choices.length];
        int current = 0;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i] == 0 ? getString(R.string.code_off)
                    : getString(R.string.wipe_after_n, choices[i]);
            if (choices[i] == Folder.wipeAfter(this)) current = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.wipe_title)
                .setSingleChoiceItems(labels, current, (d, w) -> {
                    Folder.setWipeAfter(this, choices[w]);
                    d.dismiss();
                })
                .show();
    }

    // ---- The grid ----

    private void showGrid() {
        List<LauncherActivityInfo> apps = Folder.apps(this, mFolder);
        if (apps.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(R.string.empty);
            t.setTextSize(16);
            t.setTextColor(getColor(R.color.anon_muted));
            t.setGravity(Gravity.CENTER);
            setContentView(t);
            return;
        }
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(88));
        grid.setVerticalSpacing(dp(12));
        grid.setPadding(dp(8), dp(16), dp(8), dp(16));
        grid.setAdapter(new AppAdapter(apps));
        grid.setOnItemClickListener((p, v, pos, id) ->
                Folder.launch(this, mFolder, apps.get(pos).getComponentName()));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmRemove(apps.get(pos));
            return true;
        });
        setContentView(grid);
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<LauncherActivityInfo> mApps;

        AppAdapter(List<LauncherActivityInfo> apps) {
            mApps = apps;
        }

        @Override public int getCount() { return mApps.size(); }
        @Override public Object getItem(int i) { return mApps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout cell = convert instanceof LinearLayout ? (LinearLayout) convert : appCell();
            LauncherActivityInfo a = mApps.get(i);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(
                    a.getBadgedIcon(getResources().getDisplayMetrics().densityDpi));
            ((TextView) cell.getChildAt(1)).setText(a.getLabel());
            return cell;
        }
    }

    private LinearLayout appCell() {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView label = new TextView(this);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.anon_text));
        label.setPadding(0, dp(6), 0, 0);
        cell.addView(label);
        return cell;
    }

    private void confirmRemove(LauncherActivityInfo a) {
        new AlertDialog.Builder(this)
                .setTitle(a.getLabel())
                .setItems(new CharSequence[] {getString(R.string.remove_app)}, (d, w) -> {
                    // Uninstalls the folder's copy only; the main side's app stays.
                    Context in = createContextAsUser(mFolder, 0);
                    PendingIntent done = PendingIntent.getActivity(this, 0,
                            new Intent(this, FolderActivity.class), PendingIntent.FLAG_IMMUTABLE);
                    in.getPackageManager().getPackageInstaller().uninstall(
                            a.getComponentName().getPackageName(), done.getIntentSender());
                })
                .show();
    }

    // ---- Menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        boolean open = mFolder != null && !Folder.isLocked(this, mFolder);
        if (open) {
            m.add(0, MENU_ADD, 0, R.string.add_apps);
            m.add(0, MENU_LOCK, 1, R.string.lock_now);
            m.add(0, MENU_WAYS, 2, R.string.ways_in);
            m.add(0, MENU_KEY, 3, R.string.key_title_change);
            m.add(0, MENU_WIPE, 4, R.string.wipe_title);
            m.add(0, MENU_DELETE, 5, R.string.delete_folder);
        }
        return open;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        m.clear();
        return onCreateOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD: pickApps(); return true;
            case MENU_LOCK: Folder.lock(this, mFolder); finish(); return true;
            case MENU_WAYS: waysIn(); return true;
            case MENU_DELETE: confirmDelete(); return true;
            case MENU_KEY: changeKey(); return true;
            case MENU_WIPE: wipeLimit(); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void pickApps() {
        List<LauncherActivityInfo> addable = Folder.addable(this, mFolder);
        CharSequence[] names = new CharSequence[addable.size()];
        for (int i = 0; i < names.length; i++) names[i] = addable.get(i).getLabel();
        boolean[] picked = new boolean[names.length];
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_apps)
                .setMultiChoiceItems(names, picked, (d, w, on) -> picked[w] = on)
                .setPositiveButton(R.string.save, (d, w) -> {
                    List<String> failed = new ArrayList<>();
                    for (int i = 0; i < picked.length; i++) {
                        if (!picked[i]) continue;
                        String pkg = addable.get(i).getComponentName().getPackageName();
                        try {
                            Folder.add(this, mFolder, pkg);
                        } catch (Exception e) {
                            failed.add(names[i].toString());
                        }
                    }
                    if (!failed.isEmpty()) {
                        Toast.makeText(this, String.join(", ", failed), Toast.LENGTH_LONG).show();
                    }
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void waysIn() {
        String code = getString(R.string.way_code) + ": "
                + (Folder.hasCode(this) ? "*#*#····#*#*" : getString(R.string.code_off));
        CharSequence[] items = {
                getString(R.string.way_icon), getString(R.string.way_disguise),
                getString(R.string.way_tile), code};
        Folder.Way[] ways = {Folder.Way.ICON, Folder.Way.DISGUISE, Folder.Way.TILE};
        boolean[] on = {
                Folder.isOn(this, ways[0]), Folder.isOn(this, ways[1]),
                Folder.isOn(this, ways[2]), Folder.hasCode(this)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.ways_in)
                .setMultiChoiceItems(items, on, (d, w, checked) -> {
                    if (w < 3) {
                        Folder.set(this, ways[w], checked);
                    } else if (checked) {
                        d.dismiss();
                        askCode(false);
                    } else {
                        Folder.setCode(this, null);
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void askCode(boolean firstTime) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.set_code)
                .setMessage(R.string.set_code_body)
                .setView(e)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(firstTime ? R.string.code_off : R.string.cancel, (x, w) -> {
                    if (firstTime) Folder.setCode(this, null);
                })
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = e.getText().toString();
            if (!Folder.isValidCode(code)) {
                e.setError(getString(R.string.code_bad));
                return;
            }
            Folder.setCode(this, code);
            d.dismiss();
        }));
        d.show();
    }

    private void confirmDelete() {
        EditText e = new EditText(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_folder)
                .setMessage(R.string.delete_body)
                .setView(e)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (!"DELETE".equals(e.getText().toString().trim())) return;
                    Folder.delete(this, mFolder);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
