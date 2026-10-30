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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceDeltaTest {

  /**
   * A synthetic trace that takes every rule of the format: integers that move, an unchanged line,
   * objects of a list by id removed, added and changed, a list of objects changed element by
   * element, keys added and removed, and values replaced whole (a boolean, a string, an object by a
   * string, a list emptied, refilled and shortened).
   */
  private static final String TRACE =
      """
      {"done":false,"items":[{"id":1,"kind":"a","x":10},{"id":2,"kind":"b","x":20}],"name":"n","sides":[{"n":5,"slots":[1,2]},{"n":7,"slots":[3,4]}],"step":0}
      {"done":false,"items":[{"id":1,"kind":"a","x":12},{"id":2,"kind":"b","x":20}],"name":"n","sides":[{"n":6,"slots":[1,2]},{"n":7,"slots":[3,4]}],"step":1}
      {"done":false,"items":[{"id":1,"kind":"a","x":12},{"id":2,"kind":"b","x":20}],"name":"n","sides":[{"n":6,"slots":[1,2]},{"n":7,"slots":[3,4]}],"step":1}
      {"done":false,"extra":{"k":1},"items":[{"id":2,"kind":"c","x":19},{"id":3,"kind":"a","x":-4}],"name":"m","sides":[{"n":6,"slots":[2,1]},{"n":9,"slots":[3,4]}],"step":2}
      {"done":true,"extra":{"k":3,"z":"q"},"items":[],"sides":[{"n":6,"slots":[2,1]},{"n":9,"slots":[3,4]}],"step":3}
      {"done":true,"extra":"gone","items":[{"id":5,"kind":"d","x":0}],"sides":[{"n":6,"slots":[2,1]}],"step":4}
      """;

  /** {@link #TRACE}'s delta lines, as the packer of the coverage sets encodes them. */
  private static final String DELTA =
      """
      {"done":false,"items":[{"id":1,"kind":"a","x":10},{"id":2,"kind":"b","x":20}],"name":"n","sides":[{"n":5,"slots":[1,2]},{"n":7,"slots":[3,4]}],"step":0}
      {"items":{"~":[{"id":1,"x":2}]},"sides":{"*":[{"n":1},{}]},"step":1}
      {}
      {"extra":{"k":1},"items":{"+":[{"id":3,"kind":"a","x":-4}],"-":[1],"~":[{"id":2,"kind":"c","x":-1}]},"name":"m","sides":{"*":[{"slots":[2,1]},{"n":2}]},"step":1}
      {"-":["name"],"done":true,"extra":{"k":2,"z":"q"},"items":[],"step":1}
      {"extra":"gone","items":[{"id":5,"kind":"d","x":0}],"sides":[{"n":6,"slots":[2,1]}],"step":1}
      """;

  @TempDir Path folder;

  @Test
  void theDeltaLinesGiveTheTraceBackByteForByte() throws IOException {
    assertThat(new String(TraceDelta.decode(bytes(DELTA)), StandardCharsets.UTF_8))
        .isEqualTo(TRACE);
  }

  @Test
  void aDeltaTraceIsReadFromItsDeltaFile() throws IOException {
    Files.write(folder.resolve("observations.delta.jsonl.zst"), zstd(bytes(DELTA)));

    assertThat(TraceReader.STANDARD.read(folder)).isEqualTo(bytes(TRACE));
  }

  @Test
  void aFolderWithBothTraceFilesIsRefused() throws IOException {
    Files.write(folder.resolve("observations.jsonl.zst"), zstd(bytes(TRACE)));
    Files.write(folder.resolve("observations.delta.jsonl.zst"), zstd(bytes(DELTA)));

    assertThatThrownBy(() -> TraceReader.STANDARD.read(folder))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("two trace files");
  }

  @Test
  void anEmptyDeltaFileIsAnEmptyTrace() throws IOException {
    assertThat(TraceDelta.decode(new byte[0])).isEmpty();
  }

  @Test
  void aRemovedKeyThatIsNotThereIsRefused() {
    assertRefused("{\"a\":1}\n{\"-\":[\"b\"]}\n", "removes the key \"b\"");
  }

  @Test
  void aRemovedIdThatIsNotThereIsRefused() {
    assertRefused("{\"l\":[{\"id\":1}]}\n{\"l\":{\"-\":[2]}}\n", "removes the id 2");
  }

  @Test
  void anAddedIdThatIsThereIsRefused() {
    assertRefused("{\"l\":[{\"id\":1}]}\n{\"l\":{\"+\":[{\"id\":1}]}}\n", "adds the id 1");
  }

  @Test
  void aChangedIdThatIsNotThereIsRefused() {
    assertRefused("{\"l\":[{\"id\":1,\"x\":0}]}\n{\"l\":{\"~\":[{\"id\":2,\"x\":1}]}}\n", "id 2");
  }

  @Test
  void anElementWiseDeltaOfAnotherLengthIsRefused() {
    assertRefused("{\"l\":[{\"x\":0}]}\n{\"l\":{\"*\":[{},{}]}}\n", "2 elements against 1");
  }

  @Test
  void anIdListDeltaWithAnotherKeyIsRefused() {
    assertRefused("{\"l\":[{\"id\":1}]}\n{\"l\":{\"=\":[]}}\n", "with the key =");
  }

  @Test
  void anIdListDeltaAgainstIdsNotAscendingIsRefused() {
    assertRefused("{\"l\":[{\"id\":2},{\"id\":1}]}\n{\"l\":{\"-\":[1]}}\n", "not ascending");
  }

  @Test
  void aLineThatIsNotJsonIsRefused() {
    assertThatThrownBy(() -> TraceDelta.decode(bytes("{\"a\":1}\n{\"a\":\n")))
        .isInstanceOf(IOException.class);
  }

  private static void assertRefused(String delta, String message) {
    assertThatThrownBy(() -> TraceDelta.decode(bytes(delta)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("delta line 2 does not apply")
        .hasMessageContaining(message);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] zstd(byte[] data) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (OutputStream out = new ZstdOutputStream(buffer)) {
      out.write(data);
    }
    return buffer.toByteArray();
  }
}
