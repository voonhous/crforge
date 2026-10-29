/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

/**
 * XXH3, the 128-bit variant with seed 0 and the default secret, as the xxHash specification gives
 * it (https://github.com/Cyan4973/xxHash, doc/xxhash_spec.md). The game's asset database carries
 * this hash of its contents in its header (see {@link AssetDatabase}).
 *
 * <p>Every value is an unsigned 64-bit integer held in a {@code long}: additions, multiplications
 * and shifts wrap as the specification's do.
 */
final class Xxh3 {

  private static final long PRIME32_1 = 0x9E3779B1L;
  private static final long PRIME32_2 = 0x85EBCA77L;
  private static final long PRIME32_3 = 0xC2B2AE3DL;
  private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
  private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
  private static final long PRIME64_3 = 0x165667B19E3779F9L;
  private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
  private static final long PRIME64_5 = 0x27D4EB2F165667C5L;
  private static final long PRIME_MX1 = 0x165667919E3779F9L;
  private static final long PRIME_MX2 = 0x9FB21C651E98DF25L;

  private static final int STRIPE_LEN = 64;
  private static final int SECRET_CONSUME_RATE = 8;
  private static final int MIDSIZE_MAX = 240;
  private static final int MIDSIZE_START_OFFSET = 3;
  private static final int MIDSIZE_LAST_OFFSET = 17;
  private static final int SECRET_SIZE_MIN = 136;
  private static final int SECRET_LASTACC_START = 7;
  private static final int SECRET_MERGEACCS_START = 11;

  /** The default secret of the specification, 192 bytes. */
  private static final byte[] SECRET =
      bytes(
          "b8fe6c3923a44bbe7c01812cf721ad1cded46de9839097db7240a4a4b7b3671f"
              + "cb79e64eccc0e578825ad07dccff7221b8084674f743248ee03590e6813a264c"
              + "3c2852bb91c300cb88d0658b1b532ea371644897a20df94e3819ef46a9deacd8"
              + "a8fa763fe39c343ff9dcbbc7c70b4f1d8a51e04bcdb45931c89f7ec9d9787364"
              + "eac5ac8334d3ebc3c581a0fffa1363eb170ddd51b7f0da49d316552629d4689e"
              + "2b16be587d47a1fc8ff8b8d17ad031ce45cb3a8f95160428afd7fbcabb4b407e");

  private Xxh3() {}

  /**
   * The 128-bit hash of the bytes.
   *
   * @return the low 64 bits, then the high 64 bits
   */
  static long[] hash128(byte[] input) {
    int len = input.length;
    if (len <= 16) {
      return len0to16(input, len);
    }
    if (len <= 128) {
      return len17to128(input, len);
    }
    if (len <= MIDSIZE_MAX) {
      return len129to240(input, len);
    }
    return hashLong(input, len);
  }

  private static long[] len0to16(byte[] in, int len) {
    if (len > 8) {
      long bitflipl = readLong(SECRET, 32) ^ readLong(SECRET, 40);
      long bitfliph = readLong(SECRET, 48) ^ readLong(SECRET, 56);
      long inputLo = readLong(in, 0);
      long inputHi = readLong(in, len - 8);
      long[] m = mult64to128(inputLo ^ inputHi ^ bitflipl, PRIME64_1);
      m[0] += (long) (len - 1) << 54;
      inputHi ^= bitfliph;
      m[1] += inputHi + (inputHi & 0xFFFFFFFFL) * (PRIME32_2 - 1);
      m[0] ^= Long.reverseBytes(m[1]);
      long[] h = mult64to128(m[0], PRIME64_2);
      h[1] += m[1] * PRIME64_2;
      return new long[] {avalanche(h[0]), avalanche(h[1])};
    }
    if (len >= 4) {
      long inputLo = readInt(in, 0);
      long inputHi = readInt(in, len - 4);
      long input64 = inputLo + (inputHi << 32);
      long bitflip = readLong(SECRET, 16) ^ readLong(SECRET, 24);
      long[] m = mult64to128(input64 ^ bitflip, PRIME64_1 + ((long) len << 2));
      m[1] += m[0] << 1;
      m[0] ^= m[1] >>> 3;
      m[0] ^= m[0] >>> 35;
      m[0] *= PRIME_MX2;
      m[0] ^= m[0] >>> 28;
      m[1] = avalanche(m[1]);
      return m;
    }
    if (len > 0) {
      int c1 = in[0] & 0xff;
      int c2 = in[len >> 1] & 0xff;
      int c3 = in[len - 1] & 0xff;
      int combinedl = (c1 << 16) | (c2 << 24) | c3 | (len << 8);
      int combinedh = Integer.rotateLeft(Integer.reverseBytes(combinedl), 13);
      long bitflipl = readInt(SECRET, 0) ^ readInt(SECRET, 4);
      long bitfliph = readInt(SECRET, 8) ^ readInt(SECRET, 12);
      return new long[] {
        xxh64Avalanche((combinedl & 0xFFFFFFFFL) ^ bitflipl),
        xxh64Avalanche((combinedh & 0xFFFFFFFFL) ^ bitfliph)
      };
    }
    return new long[] {
      xxh64Avalanche(readLong(SECRET, 64) ^ readLong(SECRET, 72)),
      xxh64Avalanche(readLong(SECRET, 80) ^ readLong(SECRET, 88))
    };
  }

  private static long[] len17to128(byte[] in, int len) {
    long[] acc = {len * PRIME64_1, 0};
    if (len > 32) {
      if (len > 64) {
        if (len > 96) {
          mix32(acc, in, 48, len - 64, 96);
        }
        mix32(acc, in, 32, len - 48, 64);
      }
      mix32(acc, in, 16, len - 32, 32);
    }
    mix32(acc, in, 0, len - 16, 0);
    return finish(acc, len);
  }

  private static long[] len129to240(byte[] in, int len) {
    long[] acc = {len * PRIME64_1, 0};
    for (int i = 32; i < 160; i += 32) {
      mix32(acc, in, i - 32, i - 16, i - 32);
    }
    acc[0] = avalanche(acc[0]);
    acc[1] = avalanche(acc[1]);
    for (int i = 160; i <= len; i += 32) {
      mix32(acc, in, i - 32, i - 16, MIDSIZE_START_OFFSET + i - 160);
    }
    // the last bytes, with the seed negated (0 here)
    mix32(acc, in, len - 16, len - 32, SECRET_SIZE_MIN - MIDSIZE_LAST_OFFSET - 16);
    return finish(acc, len);
  }

  private static long[] finish(long[] acc, int len) {
    long lo = acc[0] + acc[1];
    long hi = acc[0] * PRIME64_1 + acc[1] * PRIME64_4 + len * PRIME64_2;
    return new long[] {avalanche(lo), -avalanche(hi)};
  }

  private static void mix32(long[] acc, byte[] in, int first, int second, int secret) {
    acc[0] += mix16(in, first, secret);
    acc[0] ^= readLong(in, second) + readLong(in, second + 8);
    acc[1] += mix16(in, second, secret + 16);
    acc[1] ^= readLong(in, first) + readLong(in, first + 8);
  }

  private static long mix16(byte[] in, int at, int secret) {
    return fold64(
        readLong(in, at) ^ readLong(SECRET, secret),
        readLong(in, at + 8) ^ readLong(SECRET, secret + 8));
  }

  private static long[] hashLong(byte[] in, int len) {
    long[] acc = {
      PRIME32_3, PRIME64_1, PRIME64_2, PRIME64_3, PRIME64_4, PRIME32_2, PRIME64_5, PRIME32_1
    };
    int stripesPerBlock = (SECRET.length - STRIPE_LEN) / SECRET_CONSUME_RATE;
    int blockLen = STRIPE_LEN * stripesPerBlock;
    int blocks = (len - 1) / blockLen;
    for (int n = 0; n < blocks; n++) {
      accumulate(acc, in, n * blockLen, stripesPerBlock);
      scramble(acc, SECRET.length - STRIPE_LEN);
    }
    int stripes = ((len - 1) - blockLen * blocks) / STRIPE_LEN;
    accumulate(acc, in, blocks * blockLen, stripes);
    accumulate512(acc, in, len - STRIPE_LEN, SECRET.length - STRIPE_LEN - SECRET_LASTACC_START);
    long lo = mergeAccs(acc, SECRET_MERGEACCS_START, len * PRIME64_1);
    long hi =
        mergeAccs(acc, SECRET.length - STRIPE_LEN - SECRET_MERGEACCS_START, ~(len * PRIME64_2));
    return new long[] {lo, hi};
  }

  private static void accumulate(long[] acc, byte[] in, int at, int stripes) {
    for (int n = 0; n < stripes; n++) {
      accumulate512(acc, in, at + n * STRIPE_LEN, n * SECRET_CONSUME_RATE);
    }
  }

  private static void accumulate512(long[] acc, byte[] in, int at, int secret) {
    for (int i = 0; i < 8; i++) {
      long value = readLong(in, at + 8 * i);
      long key = value ^ readLong(SECRET, secret + 8 * i);
      acc[i ^ 1] += value;
      acc[i] += (key & 0xFFFFFFFFL) * (key >>> 32);
    }
  }

  private static void scramble(long[] acc, int secret) {
    for (int i = 0; i < 8; i++) {
      long a = acc[i];
      a ^= a >>> 47;
      a ^= readLong(SECRET, secret + 8 * i);
      a *= PRIME32_1;
      acc[i] = a;
    }
  }

  private static long mergeAccs(long[] acc, int secret, long start) {
    long result = start;
    for (int i = 0; i < 4; i++) {
      result +=
          fold64(
              acc[2 * i] ^ readLong(SECRET, secret + 16 * i),
              acc[2 * i + 1] ^ readLong(SECRET, secret + 16 * i + 8));
    }
    return avalanche(result);
  }

  private static long[] mult64to128(long a, long b) {
    return new long[] {a * b, Math.unsignedMultiplyHigh(a, b)};
  }

  private static long fold64(long a, long b) {
    return a * b ^ Math.unsignedMultiplyHigh(a, b);
  }

  private static long avalanche(long h) {
    h ^= h >>> 37;
    h *= PRIME_MX1;
    return h ^ (h >>> 32);
  }

  private static long xxh64Avalanche(long h) {
    h ^= h >>> 33;
    h *= PRIME64_2;
    h ^= h >>> 29;
    h *= PRIME64_3;
    return h ^ (h >>> 32);
  }

  /** A little-endian 64-bit value. */
  static long readLong(byte[] b, int at) {
    long v = 0;
    for (int i = 7; i >= 0; i--) {
      v = (v << 8) | (b[at + i] & 0xffL);
    }
    return v;
  }

  /** A little-endian 32-bit value, unsigned. */
  static long readInt(byte[] b, int at) {
    return (b[at] & 0xffL)
        | (b[at + 1] & 0xffL) << 8
        | (b[at + 2] & 0xffL) << 16
        | (b[at + 3] & 0xffL) << 24;
  }

  private static byte[] bytes(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
