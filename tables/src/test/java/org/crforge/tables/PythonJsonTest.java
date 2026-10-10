/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** JSON written as Python's json.dumps writes it; each expected text is Python's own output. */
class PythonJsonTest {

  /** A non-ASCII letter, written as a number so the source stays ASCII; it is written raw. */
  private static final char E_ACUTE = (char) 0xe9;

  @ParameterizedTest
  @CsvSource({
    "0.1, 0.1",
    "1e16, 1e+16",
    "1234567890123456.0, 1234567890123456.0",
    "0.0001, 0.0001",
    "0.00001, 1e-05",
    "1e22, 1e+22",
    "4.9e-324, 5e-324",
    "1.7976931348623157e308, 1.7976931348623157e+308",
    "-0.0, -0.0",
    "100.0, 100.0",
    "2.0, 2.0",
    "123456789.0, 123456789.0",
    "9.223372036854775807e18, 9.223372036854776e+18",
    "-1.5e-7, -1.5e-07",
    "9007199254740993.0, 9007199254740992.0",
    "2.2250738585072014e-308, 2.2250738585072014e-308"
  })
  @DisplayName("a float is written as Python's repr: the shortest decimal that reads back")
  void floats(double value, String python) {
    assertThat(PythonJson.floatRepr(value)).isEqualTo(python);
  }

  @Test
  @DisplayName("a float32 value is written with the digits of its double")
  void float32() {
    assertThat(PythonJson.floatRepr((double) 0.65f)).isEqualTo("0.6499999761581421");
    assertThat(PythonJson.floatRepr((double) 0.1f)).isEqualTo("0.10000000149011612");
  }

  @Test
  @DisplayName("indented output nests by the indent, empty containers stay on one line")
  void indented() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("b", null);
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("a", Arrays.asList(1L, inner));
    value.put("c", Map.of());
    value.put("d", List.of());
    value.put("e", "q\"\\\n\t\u0001\u007f" + E_ACUTE);
    assertThat(PythonJson.indented(value, 1))
        .isEqualTo(
            "{\n \"a\": [\n  1,\n  {\n   \"b\": null\n  }\n ],\n \"c\": {},\n \"d\": [],\n"
                + " \"e\": \"q\\\"\\\\\\n\\t\\u0001\u007f"
                + E_ACUTE
                + "\"\n}");
  }

  @Test
  @DisplayName("compact output separates items with a comma and a space")
  void compact() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("a", List.of(1L, true, "x"));
    value.put("b", 0.5);
    assertThat(PythonJson.compact(value)).isEqualTo("{\"a\": [1, true, \"x\"], \"b\": 0.5}");
  }
}
