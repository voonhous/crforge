/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.replay;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** gzip, {@code .jsonl.gz}: the format of a crawl's earlier output. */
final class GzipCompression implements ArchiveCompression {

  /** RFC 1952: ID1, ID2. */
  private static final byte[] MAGIC = {0x1f, (byte) 0x8b};

  @Override
  public String name() {
    return "gzip";
  }

  @Override
  public String suffix() {
    return ".jsonl.gz";
  }

  @Override
  public byte[] magic() {
    return MAGIC.clone();
  }

  /** Several members, one after another (a crawl appending to its output), read as one. */
  @Override
  public InputStream open(InputStream packed) throws IOException {
    return new GZIPInputStream(packed);
  }
}
