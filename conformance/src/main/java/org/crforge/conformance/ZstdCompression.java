/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import io.airlift.compress.MalformedInputException;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Zstandard, {@code observations.jsonl.zst}: the format references are written in now. */
final class ZstdCompression implements TraceCompression {

  /** RFC 8878: the frame magic number 0xFD2FB528, little-endian. */
  private static final byte[] MAGIC = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd};

  @Override
  public String name() {
    return "zstd";
  }

  @Override
  public String fileName() {
    return "observations.jsonl.zst";
  }

  @Override
  public boolean recognizes(byte[] packed) {
    return TraceCompression.startsWith(packed, MAGIC);
  }

  @Override
  public byte[] decompress(byte[] packed) throws IOException {
    try (InputStream trace = new ZstdInputStream(new ByteArrayInputStream(packed))) {
      return trace.readAllBytes();
    } catch (MalformedInputException e) {
      // the decoder reports a damaged frame unchecked; a damaged file is a read error like gzip's
      throw new IOException("not a valid zstd file: " + e.getMessage(), e);
    }
  }
}
