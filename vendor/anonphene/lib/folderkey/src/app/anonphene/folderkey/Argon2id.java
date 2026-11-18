/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 */
package app.anonphene.folderkey;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Argon2id, version 0x13 (RFC 9106). Lanes run on their own threads within each slice, as the
 * reference implementation allows, so the result is the same as computing them one by one.
 * Memory is wiped before returning.
 */
public final class Argon2id {
    private static final int BLOCK_LONGS = 128;
    private static final int SYNC_POINTS = 4;
    private static final int VERSION = 0x13;
    private static final int TYPE_ID = 2;

    private final int mLanes, mPasses, mLaneLength, mSegmentLength, mBlocks;
    private final long[] mMem;

    private Argon2id(int memoryKiB, int passes, int lanes) {
        if (lanes < 1 || lanes > 0xFFFFFF) throw new IllegalArgumentException("lanes");
        if (passes < 1) throw new IllegalArgumentException("passes");
        if (memoryKiB < 8 * lanes) throw new IllegalArgumentException("memory");
        mLanes = lanes;
        mPasses = passes;
        mSegmentLength = memoryKiB / (lanes * SYNC_POINTS);
        mLaneLength = mSegmentLength * SYNC_POINTS;
        mBlocks = mLaneLength * lanes;
        mMem = new long[mBlocks * BLOCK_LONGS];
    }

    /**
     * @param password the password (P)
     * @param salt at least 8 bytes (S)
     * @param secret optional secret (K), may be empty
     * @param ad optional associated data (X), may be empty
     */
    public static byte[] hash(byte[] password, byte[] salt, byte[] secret, byte[] ad,
            int memoryKiB, int passes, int lanes, int tagLength) {
        if (salt.length < 8) throw new IllegalArgumentException("salt too short");
        if (tagLength < 4) throw new IllegalArgumentException("tag too short");
        Argon2id a = new Argon2id(memoryKiB, passes, lanes);
        try {
            return a.run(password, salt, secret, ad, memoryKiB, tagLength);
        } finally {
            Arrays.fill(a.mMem, 0);
        }
    }

    private byte[] run(byte[] p, byte[] s, byte[] k, byte[] x, int memoryKiB, int tagLength) {
        Blake2b h = new Blake2b(64);
        h.update(le32(mLanes)).update(le32(tagLength)).update(le32(memoryKiB)).update(le32(mPasses))
                .update(le32(VERSION)).update(le32(TYPE_ID));
        h.update(le32(p.length)).update(p);
        h.update(le32(s.length)).update(s);
        h.update(le32(k.length)).update(k);
        h.update(le32(x.length)).update(x);
        byte[] h0 = h.digest();

        byte[] seed = Arrays.copyOf(h0, 72);
        for (int l = 0; l < mLanes; l++) {
            for (int c = 0; c < 2; c++) {
                putLe32(seed, 64, c);
                putLe32(seed, 68, l);
                byte[] block = hPrime(seed, 1024);
                int base = (l * mLaneLength + c) * BLOCK_LONGS;
                for (int i = 0; i < BLOCK_LONGS; i++) mMem[base + i] = Blake2b.le64(block, 8 * i);
                Arrays.fill(block, (byte) 0);
            }
        }
        Arrays.fill(seed, (byte) 0);
        Arrays.fill(h0, (byte) 0);

        for (int pass = 0; pass < mPasses; pass++) {
            for (int slice = 0; slice < SYNC_POINTS; slice++) {
                final int r = pass, sl = slice;
                if (mLanes == 1) {
                    fillSegment(r, 0, sl);
                    continue;
                }
                CountDownLatch done = new CountDownLatch(mLanes);
                RuntimeException[] failure = new RuntimeException[1];
                for (int l = 0; l < mLanes; l++) {
                    final int lane = l;
                    Thread t = new Thread(() -> {
                        try {
                            fillSegment(r, lane, sl);
                        } catch (RuntimeException e) {
                            failure[0] = e;
                        } finally {
                            done.countDown();
                        }
                    });
                    t.start();
                }
                try {
                    done.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
                if (failure[0] != null) throw failure[0];
            }
        }

        long[] c = new long[BLOCK_LONGS];
        for (int l = 0; l < mLanes; l++) {
            int base = (l * mLaneLength + mLaneLength - 1) * BLOCK_LONGS;
            for (int i = 0; i < BLOCK_LONGS; i++) c[i] ^= mMem[base + i];
        }
        byte[] cb = new byte[1024];
        for (int i = 0; i < BLOCK_LONGS; i++) putLe64(cb, 8 * i, c[i]);
        byte[] tag = hPrime(cb, tagLength);
        Arrays.fill(cb, (byte) 0);
        Arrays.fill(c, 0);
        return tag;
    }

    private void fillSegment(int pass, int lane, int slice) {
        boolean independent = pass == 0 && slice < SYNC_POINTS / 2;
        long[] address = null, input = null, zero = null;
        if (independent) {
            address = new long[BLOCK_LONGS];
            input = new long[BLOCK_LONGS];
            zero = new long[BLOCK_LONGS];
            input[0] = pass;
            input[1] = lane;
            input[2] = slice;
            input[3] = mBlocks;
            input[4] = mPasses;
            input[5] = TYPE_ID;
        }
        long[] r = new long[BLOCK_LONGS], tmp = new long[BLOCK_LONGS];
        int start = 0;
        if (pass == 0 && slice == 0) {
            start = 2;
            if (independent) nextAddresses(address, input, zero, r, tmp);
        }
        int curr = lane * mLaneLength + slice * mSegmentLength + start;
        int prev = (curr % mLaneLength == 0) ? curr + mLaneLength - 1 : curr - 1;
        for (int i = start; i < mSegmentLength; i++, curr++, prev++) {
            if (curr % mLaneLength == 1) prev = curr - 1;
            long rand;
            if (independent) {
                if (i % BLOCK_LONGS == 0) nextAddresses(address, input, zero, r, tmp);
                rand = address[i % BLOCK_LONGS];
            } else {
                rand = mMem[prev * BLOCK_LONGS];
            }
            int refLane = (int) ((rand >>> 32) % mLanes);
            if (pass == 0 && slice == 0) refLane = lane;
            int refIndex = indexAlpha(pass, slice, i, rand & 0xFFFFFFFFL, refLane == lane);
            int ref = refLane * mLaneLength + refIndex;
            fillBlock(mMem, prev * BLOCK_LONGS, mMem, ref * BLOCK_LONGS, mMem, curr * BLOCK_LONGS,
                    pass != 0, r, tmp);
        }
        Arrays.fill(r, 0);
        Arrays.fill(tmp, 0);
    }

    private int indexAlpha(int pass, int slice, int index, long rand32, boolean sameLane) {
        long area;
        if (pass == 0) {
            if (slice == 0) area = index - 1;
            else if (sameLane) area = (long) slice * mSegmentLength + index - 1;
            else area = (long) slice * mSegmentLength + (index == 0 ? -1 : 0);
        } else {
            if (sameLane) area = mLaneLength - mSegmentLength + index - 1;
            else area = mLaneLength - mSegmentLength + (index == 0 ? -1 : 0);
        }
        long rel = (rand32 * rand32) >>> 32;
        rel = area - 1 - ((area * rel) >>> 32);
        long startPos = 0;
        if (pass != 0) startPos = (slice == SYNC_POINTS - 1) ? 0 : (long) (slice + 1) * mSegmentLength;
        return (int) ((startPos + rel) % mLaneLength);
    }

    private static void nextAddresses(long[] address, long[] input, long[] zero, long[] r, long[] tmp) {
        input[6]++;
        fillBlock(zero, 0, input, 0, address, 0, false, r, tmp);
        fillBlock(zero, 0, address, 0, address, 0, false, r, tmp);
    }

    /** next = G(prev, ref), or next ^= G(prev, ref) when withXor. */
    private static void fillBlock(long[] pm, int po, long[] rm, int ro, long[] nm, int no,
            boolean withXor, long[] r, long[] tmp) {
        for (int i = 0; i < BLOCK_LONGS; i++) {
            r[i] = pm[po + i] ^ rm[ro + i];
            tmp[i] = withXor ? r[i] ^ nm[no + i] : r[i];
        }
        for (int i = 0; i < 8; i++) {
            int b = 16 * i;
            round(r, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                    b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15);
        }
        for (int i = 0; i < 8; i++) {
            int b = 2 * i;
            round(r, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                    b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113);
        }
        for (int i = 0; i < BLOCK_LONGS; i++) nm[no + i] = tmp[i] ^ r[i];
    }

    private static void round(long[] v, int v0, int v1, int v2, int v3, int v4, int v5, int v6, int v7,
            int v8, int v9, int v10, int v11, int v12, int v13, int v14, int v15) {
        gb(v, v0, v4, v8, v12);
        gb(v, v1, v5, v9, v13);
        gb(v, v2, v6, v10, v14);
        gb(v, v3, v7, v11, v15);
        gb(v, v0, v5, v10, v15);
        gb(v, v1, v6, v11, v12);
        gb(v, v2, v7, v8, v13);
        gb(v, v3, v4, v9, v14);
    }

    private static void gb(long[] v, int a, int b, int c, int d) {
        v[a] = v[a] + v[b] + 2 * (v[a] & 0xFFFFFFFFL) * (v[b] & 0xFFFFFFFFL);
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d] + 2 * (v[c] & 0xFFFFFFFFL) * (v[d] & 0xFFFFFFFFL);
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + 2 * (v[a] & 0xFFFFFFFFL) * (v[b] & 0xFFFFFFFFL);
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d] + 2 * (v[c] & 0xFFFFFFFFL) * (v[d] & 0xFFFFFFFFL);
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    /** H' from RFC 9106 section 3.3: BLAKE2b stretched to any output length. */
    static byte[] hPrime(byte[] in, int outLen) {
        if (outLen <= 64) return Blake2b.hash(outLen, le32(outLen), in);
        byte[] out = new byte[outLen];
        int r = (outLen + 31) / 32 - 2;
        byte[] v = Blake2b.hash(64, le32(outLen), in);
        System.arraycopy(v, 0, out, 0, 32);
        for (int i = 1; i < r; i++) {
            v = Blake2b.hash(64, v);
            System.arraycopy(v, 0, out, 32 * i, 32);
        }
        byte[] last = Blake2b.hash(outLen - 32 * r, v);
        System.arraycopy(last, 0, out, 32 * r, last.length);
        return out;
    }

    static byte[] le32(int x) {
        byte[] b = new byte[4];
        putLe32(b, 0, x);
        return b;
    }

    static void putLe32(byte[] b, int off, int x) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (x >>> (8 * i));
    }

    static void putLe64(byte[] b, int off, long x) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (x >>> (8 * i));
    }
}
