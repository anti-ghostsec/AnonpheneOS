/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.folderkey;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-Java checks, run by scripts/java-check.py. Reference values come from RFC 9106, RFC 7693,
 * the argon2 reference command-line tool and Python's hashlib.
 */
public final class FolderKeyCheck {
    private static int sFailures;

    public static void main(String[] args) throws Exception {
        // BLAKE2b
        eq("blake2b-512 abc", hex(Blake2b.hash(64, b("abc"))),
                "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923");
        eq("blake2b-256 empty", hex(Blake2b.hash(32, new byte[0])),
                "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8");
        byte[] big = new byte[256 * 5];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        eq("blake2b-160 1280 bytes", hex(Blake2b.hash(20, big)), "72956584b960c10c1dfa20fd6ea8c72cddb4cca3");
        Blake2b split = new Blake2b(20);
        split.update(big, 0, 7).update(big, 7, 121).update(big, 128, 1152);
        eq("blake2b streaming", hex(split.digest()), "72956584b960c10c1dfa20fd6ea8c72cddb4cca3");

        // Argon2id, RFC 9106 section 5.3.
        byte[] p = fill(32, 1), s = fill(16, 2), k = fill(8, 3), x = fill(12, 4);
        eq("argon2id RFC 9106", hex(Argon2id.hash(p, s, k, x, 32, 3, 4, 32)),
                "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659");

        // Argon2id against the reference CLI (password "correct horse battery", salt "saltysalt1234").
        byte[] pw = b("correct horse battery"), salt = b("saltysalt1234");
        eq("argon2id t2 m64 p1", hex(Argon2id.hash(pw, salt, new byte[0], new byte[0], 64, 2, 1, 32)),
                "077aea1030fa47116c66d3bfcc9147ddf65722a609a331dc3556fddaf6468875");
        eq("argon2id t3 m256 p4", hex(Argon2id.hash(pw, salt, new byte[0], new byte[0], 256, 3, 4, 32)),
                "4b351cdf05007d3492f9149a7fa99e5a12c4b3cb354875208043c3bc3fa41f78");
        eq("argon2id t1 m1024 p2", hex(Argon2id.hash(pw, salt, new byte[0], new byte[0], 1024, 1, 2, 32)),
                "bb05f541b29cd065f33f954f51649dafa106a5ca3060c30233157b7b0f024b83");
        eq("argon2id t2 m4096 p3", hex(Argon2id.hash(pw, salt, new byte[0], new byte[0], 4096, 2, 3, 32)),
                "8692b971a3574df690dfb3b239ce179fe49f8e84415f85ab72d5401ebe7578e4");
        eq("argon2id 100-byte tag", hex(Argon2id.hash(b("x"), b("saltsalt"), new byte[0], new byte[0], 64, 1, 1, 100)),
                "9bc0f124cb25ec26c2a2ad4940453344851827152b42314109ecf75e987ea715a94818bd2d6cf84578dffc15b818d546"
                + "fbcf0b056c402d3bbca17653c7b3b6362750bbc8a15bb98d13075f070d74455b74e9eef8b0637e9699133e682ea891b95a3f8e8a");

        // Keyfiles: order doesn't matter, content does.
        byte[] k1 = FolderKey.keyfileDigest(new ByteArrayInputStream(b("first keyfile")));
        byte[] k2 = FolderKey.keyfileDigest(new ByteArrayInputStream(big));
        eq("keyfile digest is blake2b-512", hex(k2), hex(Blake2b.hash(64, big)));
        eq("keyfile order", hex(FolderKey.combineKeyfiles(List.of(k1, k2))),
                hex(FolderKey.combineKeyfiles(List.of(k2, k1))));
        check("no keyfiles, no secret", FolderKey.combineKeyfiles(List.of()).length == 0);

        // Credential: small settings so the check is quick.
        FolderKey.Params small = new FolderKey.Params(64, 1, 1, fill(32, 9));
        char[] pass = "a long passphrase é".toCharArray();
        String c0 = new String(FolderKey.credential(pass, List.of(), small), StandardCharsets.US_ASCII);
        String c1 = new String(FolderKey.credential(pass, List.of(k1), small), StandardCharsets.US_ASCII);
        String c12 = new String(FolderKey.credential(pass, List.of(k1, k2), small), StandardCharsets.US_ASCII);
        String c21 = new String(FolderKey.credential(pass, List.of(k2, k1), small), StandardCharsets.US_ASCII);
        check("credential is 64 hex", c0.matches("[0-9a-f]{64}"));
        check("a keyfile changes it", !c0.equals(c1) && !c1.equals(c12));
        eq("keyfile order in credential", c12, c21);
        String decomposed = new String(FolderKey.credential("a long passphrase é".toCharArray(), List.of(), small),
                StandardCharsets.US_ASCII);
        eq("same text from another keyboard (NFC)", decomposed, c0);
        String other = new String(FolderKey.credential("a long passphrase E".toCharArray(), List.of(), small),
                StandardCharsets.US_ASCII);
        check("one character changes it", !other.equals(c0));

        // Settings round trip.
        FolderKey.Params strong = FolderKey.Params.strong(new SecureRandom());
        FolderKey.Params back = FolderKey.Params.parse(strong.serialize());
        check("params round trip", back.memoryKiB == 262144 && back.passes == 4 && back.lanes == 4
                && Arrays.equals(back.salt(), strong.salt()));
        boolean rejected = false;
        try {
            FolderKey.Params.parse("v1:64:1:1:00");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("bad params rejected", rejected);

        // Passphrase rules.
        check("short refused", FolderKey.checkPassphrase("short") != null);
        check("digits only refused", FolderKey.checkPassphrase("123456789012345") != null);
        check("repeats refused", FolderKey.checkPassphrase("aaaaaaaaaaaaaaaa") != null);
        check("32 characters accepted", FolderKey.checkPassphrase("purple otter juggles 7 cold moons") == null);
        check("257 refused", FolderKey.checkPassphrase("ab".repeat(129)) != null);

        if (sFailures > 0) {
            System.out.println(sFailures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("folderkey: all checks passed");
    }

    private static void eq(String what, String got, String want) {
        if (!got.equals(want)) {
            sFailures++;
            System.out.println("FAIL " + what + "\n  got  " + got + "\n  want " + want);
        }
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            sFailures++;
            System.out.println("FAIL " + what);
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fill(int n, int v) {
        byte[] out = new byte[n];
        Arrays.fill(out, (byte) v);
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
