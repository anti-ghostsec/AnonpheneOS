/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.folderkey;

/** BLAKE2b (RFC 7693), unkeyed, 1 to 64 bytes of output. Streaming, so keyfiles of any size work. */
public final class Blake2b {
    private static final long[] IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    };
    private static final byte[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
    };

    private final long[] mH = new long[8];
    private final byte[] mBuf = new byte[128];
    private final long[] mM = new long[16];
    private final long[] mV = new long[16];
    private final int mOutLen;
    private int mBufLen;
    private long mCountLo, mCountHi;
    private boolean mDone;

    public Blake2b(int outLen) {
        if (outLen < 1 || outLen > 64) throw new IllegalArgumentException("outLen " + outLen);
        mOutLen = outLen;
        System.arraycopy(IV, 0, mH, 0, 8);
        mH[0] ^= 0x01010000L ^ outLen;
    }

    public static byte[] hash(int outLen, byte[]... parts) {
        Blake2b b = new Blake2b(outLen);
        for (byte[] p : parts) b.update(p, 0, p.length);
        return b.digest();
    }

    public Blake2b update(byte[] in, int off, int len) {
        if (mDone) throw new IllegalStateException("digest already taken");
        while (len > 0) {
            if (mBufLen == 128) {
                count(128);
                compress(mBuf, 0, false);
                mBufLen = 0;
            }
            int n = Math.min(128 - mBufLen, len);
            System.arraycopy(in, off, mBuf, mBufLen, n);
            mBufLen += n;
            off += n;
            len -= n;
        }
        return this;
    }

    public Blake2b update(byte[] in) {
        return update(in, 0, in.length);
    }

    public byte[] digest() {
        if (mDone) throw new IllegalStateException("digest already taken");
        mDone = true;
        count(mBufLen);
        for (int i = mBufLen; i < 128; i++) mBuf[i] = 0;
        compress(mBuf, 0, true);
        byte[] out = new byte[mOutLen];
        for (int i = 0; i < mOutLen; i++) out[i] = (byte) (mH[i >> 3] >>> (8 * (i & 7)));
        java.util.Arrays.fill(mBuf, (byte) 0);
        java.util.Arrays.fill(mH, 0);
        return out;
    }

    private void count(int n) {
        long before = mCountLo;
        mCountLo += n;
        if (Long.compareUnsigned(mCountLo, before) < 0) mCountHi++;
    }

    private void compress(byte[] block, int off, boolean last) {
        for (int i = 0; i < 16; i++) mM[i] = le64(block, off + 8 * i);
        long[] v = mV;
        System.arraycopy(mH, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= mCountLo;
        v[13] ^= mCountHi;
        if (last) v[14] = ~v[14];
        for (int r = 0; r < 12; r++) {
            byte[] s = SIGMA[r % 10];
            g(v, 0, 4, 8, 12, mM[s[0]], mM[s[1]]);
            g(v, 1, 5, 9, 13, mM[s[2]], mM[s[3]]);
            g(v, 2, 6, 10, 14, mM[s[4]], mM[s[5]]);
            g(v, 3, 7, 11, 15, mM[s[6]], mM[s[7]]);
            g(v, 0, 5, 10, 15, mM[s[8]], mM[s[9]]);
            g(v, 1, 6, 11, 12, mM[s[10]], mM[s[11]]);
            g(v, 2, 7, 8, 13, mM[s[12]], mM[s[13]]);
            g(v, 3, 4, 9, 14, mM[s[14]], mM[s[15]]);
        }
        for (int i = 0; i < 8; i++) mH[i] ^= v[i] ^ v[i + 8];
    }

    private static void g(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    static long le64(byte[] b, int off) {
        long r = 0;
        for (int i = 7; i >= 0; i--) r = (r << 8) | (b[off + i] & 0xFF);
        return r;
    }
}
