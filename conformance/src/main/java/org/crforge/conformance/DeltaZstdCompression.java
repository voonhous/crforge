/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import java.io.IOException;

/**
 * Zstandard over the trace's delta lines ({@link TraceDelta}), {@code
 * observations.delta.jsonl.zst}: the coverage sets' form, about a quarter of the size of the zstd
 * trace itself.
 */
final class DeltaZstdCompression implements TraceCompression {

  private final ZstdCompression zstd = new ZstdCompression();

  @Override
  public String name() {
    return "zstd delta lines";
  }

  @Override
  public String fileName() {
    return "observations.delta.jsonl.zst";
  }

  @Override
  public boolean recognizes(byte[] packed) {
    return zstd.recognizes(packed);
  }

  @Override
  public byte[] decompress(byte[] packed) throws IOException {
    return TraceDelta.decode(zstd.decompress(packed));
  }
}
