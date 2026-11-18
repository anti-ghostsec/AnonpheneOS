/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.folderkey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns the Secure Folder's passphrase and keyfiles into the credential Android locks the
 * folder's profile with.
 *
 * <pre>
 *   K = BLAKE2b-512("AnonpheneOS keyfiles v1" || n || sorted BLAKE2b-512 of each keyfile)
 *   credential = hex(Argon2id(P = NFC passphrase, S = 32-byte salt, K, X = "AnonpheneOS Secure Folder v1"))
 * </pre>
 *
 * <p>The credential is 256 bits, which Android then binds to the phone's secure element and
 * its attempt limits. So getting into the folder takes both the phone's hardware secret and an
 * Argon2id search over the passphrase and every keyfile; neither half is enough alone. Nothing
 * here is stored except the salt and the Argon2id settings, which say nothing about the
 * passphrase. The order keyfiles are given in doesn't matter, as in VeraCrypt.
 */
public final class FolderKey {
    public static final int MIN_PASSPHRASE = 12;
    public static final int MAX_PASSPHRASE = 256;
    public static final int SALT_BYTES = 32;
    public static final int MAX_KEYFILES = 64;
    private static final byte[] KEYFILES_DOMAIN = "AnonpheneOS keyfiles v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FOLDER_DOMAIN = "AnonpheneOS Secure Folder v1".getBytes(StandardCharsets.US_ASCII);

    private FolderKey() {}

    /** Argon2id settings and salt; stored next to the folder, not secret. */
    public static final class Params {
        public final int memoryKiB, passes, lanes;
        private final byte[] mSalt;

        public Params(int memoryKiB, int passes, int lanes, byte[] salt) {
            if (memoryKiB < 8 * lanes || passes < 1 || lanes < 1 || lanes > 16) {
                throw new IllegalArgumentException("bad Argon2id settings");
            }
            if (salt.length != SALT_BYTES) throw new IllegalArgumentException("salt length");
            this.memoryKiB = memoryKiB;
            this.passes = passes;
            this.lanes = lanes;
            mSalt = salt.clone();
        }

        /** 256 MiB, 4 passes, 4 lanes: a few seconds on a recent phone, per guess. */
        public static Params strong(SecureRandom rnd) {
            byte[] salt = new byte[SALT_BYTES];
            rnd.nextBytes(salt);
            return new Params(256 * 1024, 4, 4, salt);
        }

        public byte[] salt() {
            return mSalt.clone();
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder("v1:").append(memoryKiB).append(':').append(passes)
                    .append(':').append(lanes).append(':');
            for (byte b : mSalt) sb.append(String.format("%02x", b));
            return sb.toString();
        }

        public static Params parse(String s) {
            String[] f = s == null ? new String[0] : s.trim().split(":");
            if (f.length != 5 || !f[0].equals("v1") || f[4].length() != 2 * SALT_BYTES) {
                throw new IllegalArgumentException("not Secure Folder key settings");
            }
            byte[] salt = new byte[SALT_BYTES];
            for (int i = 0; i < SALT_BYTES; i++) {
                salt[i] = (byte) Integer.parseInt(f[4].substring(2 * i, 2 * i + 2), 16);
            }
            return new Params(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), salt);
        }
    }

    /** Null when the passphrase is acceptable, else why not. */
    public static String checkPassphrase(CharSequence p) {
        if (p == null) return "Type a passphrase.";
        int n = Character.codePointCount(p, 0, p.length());
        if (n < MIN_PASSPHRASE) return "Use at least " + MIN_PASSPHRASE + " characters.";
        if (n > MAX_PASSPHRASE) return "Use at most " + MAX_PASSPHRASE + " characters.";
        boolean allDigits = true;
        for (int i = 0; i < p.length(); i++) if (!Character.isDigit(p.charAt(i))) allDigits = false;
        if (allDigits) return "Use words or letters too, not only digits.";
        int distinct = (int) p.codePoints().distinct().count();
        if (distinct < 5) return "Use more different characters.";
        return null;
    }

    /** BLAKE2b-512 of a keyfile's whole contents. The stream is read to its end, not closed. */
    public static byte[] keyfileDigest(InputStream in) throws IOException {
        Blake2b b = new Blake2b(64);
        byte[] buf = new byte[64 * 1024];
        for (int r; (r = in.read(buf)) > 0; ) b.update(buf, 0, r);
        Arrays.fill(buf, (byte) 0);
        return b.digest();
    }

    /** The Argon2id secret made from all keyfiles; empty when there are none. */
    public static byte[] combineKeyfiles(List<byte[]> digests) {
        if (digests.isEmpty()) return new byte[0];
        if (digests.size() > MAX_KEYFILES) throw new IllegalArgumentException("too many keyfiles");
        List<byte[]> sorted = new ArrayList<>(digests);
        sorted.sort(Arrays::compareUnsigned);
        Blake2b b = new Blake2b(64);
        b.update(KEYFILES_DOMAIN).update(Argon2id.le32(sorted.size()));
        for (byte[] d : sorted) {
            if (d.length != 64) throw new IllegalArgumentException("not a keyfile digest");
            b.update(d);
        }
        return b.digest();
    }

    /**
     * The folder's credential, as 64 ASCII hex digits. The caller wipes the passphrase it
     * passed and the returned array when done.
     */
    public static byte[] credential(char[] passphrase, List<byte[]> keyfileDigests, Params params) {
        String nfc = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC);
        ByteBuffer enc = StandardCharsets.UTF_8.encode(CharBuffer.wrap(nfc));
        byte[] p = new byte[enc.remaining()];
        enc.get(p);
        if (enc.hasArray()) Arrays.fill(enc.array(), (byte) 0);
        byte[] k = combineKeyfiles(keyfileDigests);
        byte[] tag = null;
        try {
            tag = Argon2id.hash(p, params.mSalt, k, FOLDER_DOMAIN,
                    params.memoryKiB, params.passes, params.lanes, 32);
            byte[] hex = new byte[64];
            for (int i = 0; i < 32; i++) {
                hex[2 * i] = (byte) "0123456789abcdef".charAt((tag[i] >> 4) & 0xF);
                hex[2 * i + 1] = (byte) "0123456789abcdef".charAt(tag[i] & 0xF);
            }
            return hex;
        } finally {
            Arrays.fill(p, (byte) 0);
            Arrays.fill(k, (byte) 0);
            if (tag != null) Arrays.fill(tag, (byte) 0);
        }
    }
}
