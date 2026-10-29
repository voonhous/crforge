/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JSON assets read as the client's parser holds them, and the forms the decoder refuses. */
class ClientJsonTest {

  @Test
  @DisplayName("keys keep their order; numbers are int64 or float32; scNull stays a string")
  void values() {
    Map<String, Object> doc =
        ClientJson.read(
            "{\"b\":{\"z\":1,\"a\":-2},\"a\":[0.65,1e2,true,null,\"scNull\"],"
                + "\"c\":18446744073709551617}");
    assertThat(doc.keySet()).containsExactly("b", "a", "c");
    Map<?, ?> b = (Map<?, ?>) doc.get("b");
    assertThat(List.copyOf(b.keySet())).isEqualTo(List.of("z", "a"));
    assertThat(b.get("a")).isEqualTo(-2L);
    assertThat((List<?>) doc.get("a"))
        .isEqualTo(Arrays.asList((double) 0.65f, 100.0, true, null, "scNull"));
    assertThat(doc.get("c")).isEqualTo(1L);
  }

  @Test
  @DisplayName("a repeated key, an escape, a root that is no object and loose JSON are refused")
  void refusals() {
    assertThatThrownBy(() -> ClientJson.read("{\"a\":1,\"a\":2}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ClientJson.read("{\"a\":\"x\\ny\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("escape");
    assertThatThrownBy(() -> ClientJson.read("[1]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("root");
    assertThatThrownBy(() -> ClientJson.read("{a:1}")).isInstanceOf(IllegalArgumentException.class);
  }
}
