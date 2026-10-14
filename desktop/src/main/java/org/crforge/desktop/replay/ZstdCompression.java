/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.replay;

import io.airlift.compress.MalformedInputException;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Zstandard, {@code .jsonl.zst}: the format a crawl writes its output in. */
final class ZstdCompression implements ArchiveCompression {

  /** RFC 8878: the frame magic number 0xFD2FB528, little-endian. */
  private static final byte[] MAGIC = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd};

  @Override
  public String name() {
    return "zstd";
  }

  @Override
  public String suffix() {
    return ".jsonl.zst";
  }

  @Override
  public byte[] magic() {
    return MAGIC.clone();
  }

  /** Several frames, one after another, read as one. */
  @Override
  public InputStream open(InputStream packed) {
    return new FilterInputStream(new ZstdInputStream(packed)) {
      // the decoder reports a damaged frame unchecked; a damaged file is a read error like gzip's
      @Override
      public int read() throws IOException {
        try {
          return super.read();
        } catch (MalformedInputException e) {
          throw damaged(e);
        }
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
          return super.read(buffer, offset, length);
        } catch (MalformedInputException e) {
          throw damaged(e);
        }
      }
    };
  }

  private static IOException damaged(MalformedInputException e) {
    return new IOException("not a valid zstd file: " + e.getMessage(), e);
  }
}
