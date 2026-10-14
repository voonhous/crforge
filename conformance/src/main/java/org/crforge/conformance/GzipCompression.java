/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** gzip, {@code observations.jsonl.gz}: the format of the earlier references. */
final class GzipCompression implements TraceCompression {

  /** RFC 1952: ID1, ID2. */
  private static final byte[] MAGIC = {0x1f, (byte) 0x8b};

  @Override
  public String name() {
    return "gzip";
  }

  @Override
  public String fileName() {
    return "observations.jsonl.gz";
  }

  @Override
  public boolean recognizes(byte[] packed) {
    return TraceCompression.startsWith(packed, MAGIC);
  }

  @Override
  public byte[] decompress(byte[] packed) throws IOException {
    try (InputStream trace = new GZIPInputStream(new ByteArrayInputStream(packed))) {
      return trace.readAllBytes();
    }
  }
}
