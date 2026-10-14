/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.replay;

import java.io.IOException;
import java.io.InputStream;

/**
 * One way a crawl's output ({@link ReplayArchive}) is compressed: the strategies the archive
 * chooses between, by a file's first bytes.
 *
 * <p>A format names the file name suffix a crawl writes it under and the magic number every file of
 * the format starts with, and decompresses a file as a stream: a crawl's output can be gigabytes
 * uncompressed, so it is never read whole.
 */
interface ArchiveCompression {

  /** The format's name in messages, e.g. {@code zstd}. */
  String name();

  /** The file name suffix of a crawl's output in this format, e.g. {@code .jsonl.zst}. */
  String suffix();

  /** The magic number every file of this format starts with. */
  byte[] magic();

  /**
   * The uncompressed content of a file in this format, read as it is needed.
   *
   * @param packed the file, from its first byte
   * @throws IOException when the stream cannot be opened; a file damaged further on fails as it is
   *     read, with an {@link IOException}
   */
  InputStream open(InputStream packed) throws IOException;

  /** Whether {@code head}, a file's first bytes, starts with this format's magic number. */
  default boolean recognizes(byte[] head) {
    byte[] magic = magic();
    if (head.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (head[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }
}
