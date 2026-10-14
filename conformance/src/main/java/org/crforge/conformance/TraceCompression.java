/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import java.io.IOException;

/**
 * One way a reference battle's trace ({@code observations.jsonl}) is compressed in its folder: the
 * strategies {@link TraceReader} chooses between.
 *
 * <p>A format names the trace file it is published under and the magic number every file of the
 * format starts with, and decodes the file. The reference's {@code trace_sha256} names the
 * uncompressed trace, so a reference keeps its digests whichever way it is packed.
 */
interface TraceCompression {

  /** The format's name in messages, e.g. {@code zstd}. */
  String name();

  /** The trace file's name in a reference battle folder in this format. */
  String fileName();

  /** Whether {@code packed} starts with the magic number of this format. */
  boolean recognizes(byte[] packed);

  /**
   * The uncompressed trace of a file in this format.
   *
   * @throws IOException when the bytes are not a whole, valid file of the format
   */
  byte[] decompress(byte[] packed) throws IOException;

  /** Whether {@code bytes} starts with {@code magic}. */
  static boolean startsWith(byte[] bytes, byte[] magic) {
    if (bytes.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (bytes[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }
}
