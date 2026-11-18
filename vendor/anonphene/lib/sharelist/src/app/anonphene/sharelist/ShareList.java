/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.sharelist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The share list: which apps the system share sheet may offer, for every app or for one app.
 *
 * <p>It is stored as text in the per-user secure setting {@link #SETTING}, one rule a line:
 *
 * <pre>
 *   *=org.example.mail,org.example.chat      apps offered when sharing from any app
 *   app.enclave=org.example.chat              apps offered when sharing from Enclave
 *   org.example.camera=                       sharing from the camera offers nothing
 * </pre>
 *
 * <p>A target is a package name, or one activity of it written as {@code package/.Activity}
 * or {@code package/full.class.Name}. An app's own rule replaces the {@code *} rule. With no
 * rule that applies, every app is offered (the list is off). Lines that don't parse are
 * skipped, so a bad line can't hide the whole sheet or open it up.
 *
 * <p>This class has no Android dependencies so the system share sheet and the Privacy app read
 * and write the same format. tests/share_list_vectors.json holds the cases both must pass.
 */
public final class ShareList {
    /** Settings.Secure key holding the rules. */
    public static final String SETTING = "anon_share_targets";
    /** Settings.Secure key: 1 hides people and conversations (direct share) in the sheet. */
    public static final String SETTING_HIDE_PEOPLE = "anon_share_hide_people";
    /** The rule used for apps without their own. */
    public static final String ANY_APP = "*";

    private final TreeMap<String, Set<String>> mRules;

    private ShareList(TreeMap<String, Set<String>> rules) {
        mRules = rules;
    }

    public static ShareList empty() {
        return new ShareList(new TreeMap<>());
    }

    /** Reads the stored text. {@code null} or blank gives an empty list (filter off). */
    public static ShareList parse(String text) {
        TreeMap<String, Set<String>> rules = new TreeMap<>();
        if (text == null) return new ShareList(rules);
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String source = line.substring(0, eq).trim();
            if (!source.equals(ANY_APP) && !isPackageName(source)) continue;
            Set<String> targets = new LinkedHashSet<>();
            boolean ok = true;
            for (String t : line.substring(eq + 1).split(",", -1)) {
                String target = t.trim();
                if (target.isEmpty()) continue;
                if (!isTarget(target)) {
                    ok = false;
                    break;
                }
                targets.add(target);
            }
            if (ok) rules.put(source, targets);
        }
        return new ShareList(rules);
    }

    /** True when sharing from {@code source} is limited by a rule. */
    public boolean limits(String source) {
        return ruleFor(source) != null;
    }

    /**
     * May the sheet offer the activity {@code className} of {@code targetPackage} when
     * {@code source} shares? {@code source} may be null when the sender is unknown, in which
     * case the {@code *} rule applies.
     */
    public boolean allows(String source, String targetPackage, String className) {
        Set<String> rule = ruleFor(source);
        if (rule == null) return true;
        if (rule.contains(targetPackage)) return true;
        if (className == null) return false;
        if (rule.contains(targetPackage + "/" + className)) return true;
        String prefix = targetPackage + ".";
        return className.startsWith(prefix)
                && rule.contains(targetPackage + "/" + className.substring(targetPackage.length()));
    }

    /** The targets of the rule that applies to {@code source}, or null when none does. */
    public Set<String> ruleFor(String source) {
        if (source != null) {
            Set<String> own = mRules.get(source);
            if (own != null) return Collections.unmodifiableSet(own);
        }
        Set<String> any = mRules.get(ANY_APP);
        return any == null ? null : Collections.unmodifiableSet(any);
    }

    /** True when {@code source} has a rule of its own (not just the {@code *} rule). */
    public boolean hasOwnRule(String source) {
        return mRules.containsKey(source);
    }

    /** The apps with a rule of their own, sorted. */
    public List<String> sourcesWithOwnRule() {
        List<String> out = new ArrayList<>();
        for (String s : mRules.keySet()) if (!s.equals(ANY_APP)) out.add(s);
        return out;
    }

    /** Sets the rule for {@code source} ({@link #ANY_APP} for every app). Invalid targets are dropped. */
    public ShareList with(String source, Set<String> targets) {
        if (!source.equals(ANY_APP) && !isPackageName(source)) {
            throw new IllegalArgumentException("not a package name: " + source);
        }
        TreeMap<String, Set<String>> rules = copy();
        Set<String> clean = new LinkedHashSet<>();
        for (String t : targets) if (t != null && isTarget(t.trim())) clean.add(t.trim());
        rules.put(source, clean);
        return new ShareList(rules);
    }

    /** Removes {@code source}'s rule, so it falls back to the {@code *} rule. */
    public ShareList without(String source) {
        TreeMap<String, Set<String>> rules = copy();
        rules.remove(source);
        return new ShareList(rules);
    }

    /** The text to store. {@code *} comes first, then apps in order; targets keep their order. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        Set<String> any = mRules.get(ANY_APP);
        if (any != null) appendRule(sb, ANY_APP, any);
        for (Map.Entry<String, Set<String>> e : mRules.entrySet()) {
            if (!e.getKey().equals(ANY_APP)) appendRule(sb, e.getKey(), e.getValue());
        }
        return sb.toString();
    }

    private static void appendRule(StringBuilder sb, String source, Set<String> targets) {
        sb.append(source).append('=').append(String.join(",", targets)).append('\n');
    }

    private TreeMap<String, Set<String>> copy() {
        TreeMap<String, Set<String>> out = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : mRules.entrySet()) {
            out.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        return out;
    }

    /** Android package name: two or more dot-separated parts of [A-Za-z0-9_], not starting with a digit. */
    public static boolean isPackageName(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length < 2) return false;
        for (String p : parts) {
            if (p.isEmpty() || Character.isDigit(p.charAt(0))) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_';
                if (!ok) return false;
            }
        }
        return true;
    }

    /** A package name, or {@code package/.Class} / {@code package/full.Class$Inner}. */
    public static boolean isTarget(String s) {
        if (s == null) return false;
        int slash = s.indexOf('/');
        if (slash < 0) return isPackageName(s);
        if (s.indexOf('/', slash + 1) >= 0) return false;
        String cls = s.substring(slash + 1);
        if (cls.startsWith(".")) cls = "x" + cls;
        if (cls.isEmpty() || cls.endsWith(".")) return false;
        for (int i = 0; i < cls.length(); i++) {
            char c = cls.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '$';
            if (!ok) return false;
        }
        return isPackageName(s.substring(0, slash));
    }
}
