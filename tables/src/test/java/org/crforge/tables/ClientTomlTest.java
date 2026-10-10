/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TOML read as the client's parser reads it. */
class ClientTomlTest {

  @Test
  @DisplayName("tables keep their keys in file order, with ints as longs and floats as doubles")
  void values() {
    Map<String, Object> doc = ClientToml.read("[A]\nz = 1\na = 0.5\nl = [1, \"x\"]\n");
    assertThat(doc.get("A")).isEqualTo(Map.of("z", 1L, "a", 0.5, "l", List.of(1L, "x")));
    assertThat(keys(doc.get("A"))).containsExactly("z", "a", "l");
  }

  @Test
  @DisplayName("a repeated [A] header merges its body into the first, after the first's keys")
  void repeatedHeader() {
    Map<String, Object> doc = ClientToml.read("[A]\nx = 1\n[B]\ny = 2\n[A]\nz = 3\n");
    assertThat(keys(doc.get("A"))).containsExactly("x", "z");
    assertThat(doc.keySet()).containsExactly("A", "B");
  }

  @Test
  @DisplayName("a key both bodies of a repeated header set is refused")
  void repeatedKey() {
    assertThatThrownBy(() -> ClientToml.read("[A]\nx = 1\n[A]\nx = 2\n"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("repeated array-of-tables headers are TOML and are not merged")
  void arraysOfTables() {
    assertThat(ClientToml.mergeRepeatedHeaders("[[A]]\nx = 1\n[[A]]\nx = 2\n")).isNull();
    Map<String, Object> doc = ClientToml.read("[[A]]\nx = 1\n[[A]]\nx = 2\n");
    assertThat((List<?>) doc.get("A")).hasSize(2);
  }

  @Test
  @DisplayName("the client's view of a value: floats as float32, scNull as null")
  void clientValues() {
    Map<String, Object> doc = LoadModel.clientValues(ClientToml.read("x = 0.65\ny = \"scNull\"\n"));
    assertThat(doc.get("x")).isEqualTo((double) 0.65f);
    assertThat(doc).containsEntry("y", null);
  }

  @SuppressWarnings("unchecked")
  private static List<String> keys(Object table) {
    return List.copyOf(((Map<String, Object>) table).keySet());
  }
}
