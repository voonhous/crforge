/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.airlift.compress.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceReaderTest {

  private static final byte[] TRACE =
      "{\"tick\":0}\n{\"tick\":1}\n".getBytes(StandardCharsets.UTF_8);

  @TempDir Path folder;

  @Test
  void aZstdTraceIsReadFromItsZstFile() throws IOException {
    Files.write(folder.resolve("observations.jsonl.zst"), zstd(TRACE));

    assertThat(TraceReader.STANDARD.read(folder)).isEqualTo(TRACE);
  }

  @Test
  void aZstdFrameOfAnotherEncoderIsRead() throws IOException {
    // 40 lines compressed by the zstandard Python package at level 1 (a frame with its content
    // size and compressed blocks), as the published references are written
    StringBuilder expected = new StringBuilder();
    for (int tick = 0; tick < 40; tick++) {
      expected.append("{\"rng\":7,\"tick\":").append(tick).append("}\n");
    }
    Files.write(
        folder.resolve("observations.jsonl.zst"),
        HexFormat.of()
            .parseHex(
                "28b52ffd601602350400c4037b22726e67223a372c227469636b223a307d0a313233343536373839"
                    + "3131313131313131313232323232323232323233333333333333333333397d0a272080a3d203"
                    + "8924376189482e6113494ec212915cc224925cc226929c849dc892e494252c5922c8923b9630"
                    + "6389144b8e58c2c31213d2ac27def59827bcf3184fbc631e6b48b403"));

    assertThat(new String(TraceReader.STANDARD.read(folder), StandardCharsets.UTF_8))
        .isEqualTo(expected.toString());
  }

  @Test
  void aFolderWithoutATraceIsRefusedNamingEveryFileItLookedFor() {
    assertThatThrownBy(() -> new TraceReader(List.of(new ZstdCompression(), STORED)).read(folder))
        .isInstanceOf(NoSuchFileException.class)
        .hasMessageContaining("observations.jsonl.zst")
        .hasMessageContaining("observations.jsonl.stored");
  }

  @Test
  void aGzipTraceIsNoLongerRead() throws IOException {
    // the references were repacked in zstd; a gzip trace left behind is not a trace file
    Files.write(folder.resolve("observations.jsonl.gz"), TRACE);

    assertThatThrownBy(() -> TraceReader.STANDARD.read(folder))
        .isInstanceOf(NoSuchFileException.class)
        .hasMessageContaining("observations.jsonl.zst");
  }

  @Test
  void aFolderWithTwoTracesIsRefused() throws IOException {
    Files.write(folder.resolve("observations.jsonl.stored"), TRACE);
    Files.write(folder.resolve("observations.jsonl.zst"), zstd(TRACE));

    assertThatThrownBy(() -> new TraceReader(List.of(new ZstdCompression(), STORED)).read(folder))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("two trace files");
  }

  @Test
  void aFileNotInItsNamedFormatIsRefused() throws IOException {
    Files.write(folder.resolve("observations.jsonl.zst"), TRACE);

    assertThatThrownBy(() -> TraceReader.STANDARD.read(folder))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("observations.jsonl.zst is not zstd");
  }

  @Test
  void aCorruptZstdFrameIsAnIoError() throws IOException {
    byte[] packed = zstd(TRACE);
    Files.write(folder.resolve("observations.jsonl.zst"), Arrays.copyOf(packed, 6));

    assertThatThrownBy(() -> TraceReader.STANDARD.read(folder)).isInstanceOf(IOException.class);
  }

  @Test
  void theReaderTriesOnlyTheFormatsItIsGiven() throws IOException {
    Files.write(folder.resolve("observations.jsonl.zst"), zstd(TRACE));
    TraceReader storedOnly = new TraceReader(List.of(STORED));

    assertThatThrownBy(() -> storedOnly.read(folder)).isInstanceOf(NoSuchFileException.class);
  }

  /**
   * A second format for the reader's rules between formats: the trace stored as is, under its own
   * file name, every file recognized.
   */
  private static final TraceCompression STORED =
      new TraceCompression() {
        @Override
        public String name() {
          return "stored";
        }

        @Override
        public String fileName() {
          return "observations.jsonl.stored";
        }

        @Override
        public boolean recognizes(byte[] packed) {
          return true;
        }

        @Override
        public byte[] decompress(byte[] packed) {
          return packed;
        }
      };

  private static byte[] zstd(byte[] trace) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream out = new ZstdOutputStream(bytes)) {
      out.write(trace);
    }
    return bytes.toByteArray();
  }
}
