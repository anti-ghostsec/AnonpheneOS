/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.privacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import app.anonphene.sharelist.ShareList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** AnonpheneOS's own page in Settings. Plain framework widgets, so it stays small. */
public class PrivacyActivity extends Activity {
    private static final int PICK_SHRED = 1;

    private final List<Row> mRows = new ArrayList<>();
    private ArrayAdapter<Row> mAdapter;

    private static final class Row {
        final CharSequence title;
        final CharSequence summary;
        final Runnable action;

        Row(CharSequence title, CharSequence summary, Runnable action) {
            this.title = title;
            this.summary = summary;
            this.action = action;
        }
    }

    @Override
    public void setContentView(View v) {
        super.setContentView(Bars.fit(this, v));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ListView list = new ListView(this);
        mAdapter = new ArrayAdapter<Row>(this, 0, mRows) {
            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout v = convert instanceof LinearLayout ? (LinearLayout) convert : row();
                Row r = getItem(i);
                ((TextView) v.getChildAt(0)).setText(r.title);
                ((TextView) v.getChildAt(1)).setText(r.summary);
                return v;
            }
        };
        list.setDivider(new android.graphics.drawable.ColorDrawable(getColor(R.color.anon_border)));
        list.setDividerHeight(1);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Runnable a = mRows.get(pos).action;
            if (a != null) a.run();
        });
        setContentView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        ShareList sl = Store.shareList(this);
        mRows.clear();
        java.util.Set<String> any = sl.ruleFor(null);
        mRows.add(new Row(getString(R.string.share_all),
                any == null ? getString(R.string.share_all_summary_off)
                        : getString(R.string.share_all_summary_on, any.size()),
                () -> editTargets(ShareList.ANY_APP)));
        mRows.add(new Row(getString(R.string.share_one),
                getString(R.string.share_one_summary, sl.sourcesWithOwnRule().size()),
                this::pickSource));
        java.util.List<NumberHolder> holders = numberHolders();
        int blank = 0;
        java.util.Set<String> blanked = Store.packages(this, Store.PHONE_NUMBER_BLANK);
        for (NumberHolder h : holders) if (blanked.contains(h.pkg)) blank++;
        mRows.add(new Row(getString(R.string.phone_number),
                getString(R.string.phone_number_summary, holders.size(), blank), this::editPhoneNumber));
        mRows.add(new Row(getString(R.string.mobile_data),
                getString(R.string.mobile_data_summary, Store.packages(this, Store.MOBILE_DATA_ALLOWED).size()),
                this::editMobileData));
        mRows.add(new Row(getString(R.string.folder_vpn),
                getString(Store.folderViaMainVpn(this) ? R.string.folder_vpn_on : R.string.folder_vpn_off),
                () -> {
                    Store.setFolderViaMainVpn(this, !Store.folderViaMainVpn(this));
                    rebuild();
                }));
        boolean hide = Store.hidePeople(this);
        mRows.add(new Row(getString(R.string.hide_people), getString(hide ? R.string.on : R.string.off),
                () -> {
                    Store.setHidePeople(this, !Store.hidePeople(this));
                    rebuild();
                }));
        mRows.add(new Row(getString(R.string.secure_folder), getString(R.string.secure_folder_summary),
                () -> startActivity(new Intent("app.anonphene.action.OPEN_SECURE_FOLDER")
                        .setPackage("app.anonphene.securefolder"))));
        mRows.add(new Row(getString(R.string.clear), getString(R.string.clear_summary), () -> {
            new Thread(() -> {
                Cleaner.clearTraces(this);
                runOnUiThread(() -> Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show());
            }).start();
        }));
        mRows.add(new Row(getString(R.string.shred), getString(R.string.shred_summary), () ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), PICK_SHRED)));
        mRows.add(new Row(getString(R.string.restart), getString(R.string.restart_summary), () ->
                new AlertDialog.Builder(this)
                        .setMessage(R.string.restart_confirm)
                        .setPositiveButton(R.string.restart_button, (x, w) ->
                                getSystemService(android.os.PowerManager.class).reboot(null))
                        .setNegativeButton(R.string.cancel, null)
                        .show()));
        String level = SystemProperties.get("persist.log.tag", "");
        mRows.add(new Row(getString(R.string.logs),
                "S".equals(level) ? getString(R.string.logs_on, level)
                        : getString(R.string.logs_off, level.isEmpty() ? "default" : level),
                null));
        mAdapter.notifyDataSetChanged();
    }

    /** A row: title over a muted one-line summary, as in Enclave's settings. */
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        int dp = Math.round(getResources().getDisplayMetrics().density);
        v.setPadding(24 * dp, 18 * dp, 24 * dp, 18 * dp);
        TextView title = new TextView(this);
        title.setTextSize(17);
        title.setTextColor(getColor(R.color.anon_text));
        TextView summary = new TextView(this);
        summary.setTextSize(14);
        summary.setTextColor(getColor(R.color.anon_muted));
        summary.setPadding(0, 4 * dp, 0, 0);
        v.addView(title);
        v.addView(summary);
        return v;
    }

    // ---- Share list ----

    /** Every app that can receive a share, as package → label, by label. */
    private Map<String, CharSequence> shareTargets() {
        PackageManager pm = getPackageManager();
        Intent send = new Intent(Intent.ACTION_SEND).setType("*/*");
        List<ResolveInfo> found = pm.queryIntentActivities(send, PackageManager.MATCH_ALL);
        Intent sendText = new Intent(Intent.ACTION_SEND).setType("text/plain");
        found.addAll(pm.queryIntentActivities(sendText, PackageManager.MATCH_ALL));
        found.sort((a, c) -> a.loadLabel(pm).toString().compareToIgnoreCase(c.loadLabel(pm).toString()));
        Map<String, CharSequence> out = new LinkedHashMap<>();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (!out.containsKey(pkg)) out.put(pkg, r.activityInfo.applicationInfo.loadLabel(pm));
        }
        return out;
    }

    private void editTargets(String source) {
        ShareList sl = Store.shareList(this);
        Map<String, CharSequence> targets = shareTargets();
        List<String> pkgs = new ArrayList<>(targets.keySet());
        // An app without its own list starts from the every-app list (ruleFor falls back to it).
        Set<String> current = sl.ruleFor(source);
        // Entries in the list that aren't installed now (or name one activity) are kept as they
        // are, so reinstalling an app brings it back.
        Set<String> kept = new LinkedHashSet<>();
        if (sl.hasOwnRule(source)) {
            for (String t : current) if (!targets.containsKey(t)) kept.add(t);
        }

        CharSequence[] labels = new CharSequence[pkgs.size()];
        boolean[] on = new boolean[pkgs.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = targets.get(pkgs.get(i));
            on[i] = current != null && current.contains(pkgs.get(i));
        }
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(R.string.pick_targets)
                .setMultiChoiceItems(labels, on, (x, w, c) -> on[w] = c)
                .setPositiveButton(R.string.save, (x, w) -> {
                    Set<String> chosen = new LinkedHashSet<>(kept);
                    for (int i = 0; i < on.length; i++) if (on[i]) chosen.add(pkgs.get(i));
                    Store.save(this, Store.shareList(this).with(source, chosen));
                    rebuild();
                })
                .setNegativeButton(R.string.cancel, null);
        // The every-app list can be switched off; an app's own list can be dropped.
        d.setNeutralButton(ShareList.ANY_APP.equals(source) ? R.string.list_off : R.string.use_every_app,
                (x, w) -> {
                    Store.save(this, Store.shareList(this).without(source));
                    rebuild();
                });
        d.show();
    }

    private void pickSource() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        apps.sort((a, c) -> a.loadLabel(pm).toString().compareToIgnoreCase(c.loadLabel(pm).toString()));
        ShareList sl = Store.shareList(this);
        List<String> pkgs = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            if (pkgs.contains(pkg) || !ShareList.isPackageName(pkg)) continue;
            pkgs.add(pkg);
            labels.add(sl.hasOwnRule(pkg) ? r.loadLabel(pm) + "  ✓" : r.loadLabel(pm));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_source)
                .setItems(labels.toArray(new CharSequence[0]), (x, w) -> editTargets(pkgs.get(w)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Phone number ----

    private static final class NumberHolder {
        final String pkg;
        final CharSequence label;

        NumberHolder(String pkg, CharSequence label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /**
     * Every app that can get the number on AnonpheneOS, with why: the phone-number permission,
     * the default SMS or phone app, or the SIM's carrier app.
     */
    private java.util.List<NumberHolder> numberHolders() {
        PackageManager pm = getPackageManager();
        Map<String, java.util.List<String>> why = new java.util.TreeMap<>();
        for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS))) {
            if (pi.requestedPermissions == null) continue;
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                if (android.Manifest.permission.READ_PHONE_NUMBERS.equals(pi.requestedPermissions[i])
                        && (pi.requestedPermissionsFlags[i] & android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                        && (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                    why.computeIfAbsent(pi.packageName, k -> new ArrayList<>()).add(getString(R.string.why_permission));
                }
            }
        }
        String sms = android.provider.Telephony.Sms.getDefaultSmsPackage(this);
        if (sms != null) why.computeIfAbsent(sms, k -> new ArrayList<>()).add(getString(R.string.why_sms));
        android.telecom.TelecomManager tm = getSystemService(android.telecom.TelecomManager.class);
        String dialer = tm != null ? tm.getDefaultDialerPackage() : null;
        if (dialer != null) why.computeIfAbsent(dialer, k -> new ArrayList<>()).add(getString(R.string.why_dialer));
        try {
            android.telephony.TelephonyManager tel = getSystemService(android.telephony.TelephonyManager.class);
            for (String c : tel.getCarrierPrivilegedPackagesForAllActiveSubscriptions()) {
                why.computeIfAbsent(c, k -> new ArrayList<>()).add(getString(R.string.why_carrier));
            }
        } catch (RuntimeException e) {
            // no SIM, or no telephony on this device
        }
        java.util.List<NumberHolder> out = new ArrayList<>();
        for (Map.Entry<String, java.util.List<String>> e : why.entrySet()) {
            CharSequence name;
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(e.getKey(), 0));
            } catch (PackageManager.NameNotFoundException ex) {
                continue;
            }
            out.add(new NumberHolder(e.getKey(), name + " · " + String.join(", ", e.getValue())));
        }
        return out;
    }

    private void editPhoneNumber() {
        java.util.List<NumberHolder> holders = numberHolders();
        if (holders.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(R.string.phone_number_none)
                    .setPositiveButton(R.string.ok, null).show();
            return;
        }
        Set<String> blanked = Store.packages(this, Store.PHONE_NUMBER_BLANK);
        CharSequence[] labels = new CharSequence[holders.size()];
        boolean[] on = new boolean[holders.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = holders.get(i).label;
            on[i] = blanked.contains(holders.get(i).pkg);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.phone_number_title)
                .setMultiChoiceItems(labels, on, (d, w, c) -> on[w] = c)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Set<String> next = Store.packages(this, Store.PHONE_NUMBER_BLANK);
                    for (int i = 0; i < on.length; i++) {
                        if (on[i]) next.add(holders.get(i).pkg); else next.remove(holders.get(i).pkg);
                    }
                    Store.setPackages(this, Store.PHONE_NUMBER_BLANK, next);
                    rebuild();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Mobile data ----

    /** Installed (not built-in) apps that use the internet, by name. */
    private java.util.List<android.content.pm.ApplicationInfo> internetApps() {
        PackageManager pm = getPackageManager();
        java.util.List<android.content.pm.ApplicationInfo> out = new ArrayList<>();
        for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS))) {
            if ((pi.applicationInfo.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                    | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
            if (pi.requestedPermissions == null) continue;
            if (java.util.Arrays.asList(pi.requestedPermissions).contains(android.Manifest.permission.INTERNET)) {
                out.add(pi.applicationInfo);
            }
        }
        out.sort((a, b) -> pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString()));
        return out;
    }

    private void editMobileData() {
        PackageManager pm = getPackageManager();
        java.util.List<android.content.pm.ApplicationInfo> apps = internetApps();
        if (apps.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(R.string.mobile_data_none)
                    .setPositiveButton(R.string.ok, null).show();
            return;
        }
        Set<String> allowed = Store.packages(this, Store.MOBILE_DATA_ALLOWED);
        CharSequence[] labels = new CharSequence[apps.size()];
        boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = pm.getApplicationLabel(apps.get(i));
            on[i] = allowed.contains(apps.get(i).packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.mobile_data_title)
                .setMultiChoiceItems(labels, on, (d, w, c) -> on[w] = c)
                .setPositiveButton(R.string.save, (d, w) -> {
                    // Apps no longer installed keep their place in the list, so a reinstall
                    // doesn't silently lose (or gain) mobile data.
                    Set<String> next = Store.packages(this, Store.MOBILE_DATA_ALLOWED);
                    for (int i = 0; i < on.length; i++) {
                        if (on[i]) next.add(apps.get(i).packageName); else next.remove(apps.get(i).packageName);
                    }
                    Store.setPackages(this, Store.MOBILE_DATA_ALLOWED, next);
                    rebuild();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Shredding ----

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_SHRED || result != RESULT_OK || data == null) return;
        List<Uri> files = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) files.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            files.add(data.getData());
        }
        if (files.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.shred_confirm, files.size()))
                .setPositiveButton(R.string.shred_button, (x, w) -> new Thread(() -> {
                    int n = Cleaner.shred(this, files);
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.shredded, n, files.size()), Toast.LENGTH_LONG).show());
                }).start())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
