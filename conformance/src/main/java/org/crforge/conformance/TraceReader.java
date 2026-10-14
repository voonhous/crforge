/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads a reference battle's trace in whichever of its formats ({@link TraceCompression}) it was
 * published.
 *
 * <p>The folder must hold exactly one trace file among the formats' file names, and that file must
 * start with its format's magic number. A folder without one, with two, or whose file is not in its
 * named format is refused with an {@link IOException}, never guessed at: the case is then invalid,
 * with the reason.
 */
final class TraceReader {

  /** Every format a reference's trace is published in: zstd, and gzip for the earlier ones. */
  static final TraceReader STANDARD =
      new TraceReader(List.of(new ZstdCompression(), new GzipCompression()));

  private final List<TraceCompression> formats;

  TraceReader(List<TraceCompression> formats) {
    this.formats = List.copyOf(formats);
  }

  /** The uncompressed trace of the reference battle in {@code folder}. */
  byte[] read(Path folder) throws IOException {
    List<TraceCompression> present =
        formats.stream().filter(format -> Files.exists(folder.resolve(format.fileName()))).toList();
    if (present.isEmpty()) {
      throw new NoSuchFileException(
          folder.toString(),
          null,
          "no trace file: none of "
              + formats.stream().map(TraceCompression::fileName).collect(Collectors.joining(", ")));
    }
    if (present.size() > 1) {
      throw new IOException(
          "two trace files in "
              + folder
              + ": "
              + present.stream().map(TraceCompression::fileName).collect(Collectors.joining(", ")));
    }
    TraceCompression format = present.get(0);
    byte[] packed = Files.readAllBytes(folder.resolve(format.fileName()));
    if (!format.recognizes(packed)) {
      throw new IOException(format.fileName() + " is not " + format.name() + " in " + folder);
    }
    return format.decompress(packed);
  }
}
