/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JSON asset of the game's data as the client's JSON parser holds it, in the node tree its
 * TOML parser builds too, so that the rest of the decoder reads both alike: objects as {@link
 * LinkedHashMap} with their keys in the order written, arrays as {@link List}, an integer as a
 * {@link Long} (wrapping to 64 bits as the client accumulates it), a number with a fraction or an
 * exponent as a float32 held in a {@link Double}, {@code null} as null, strings and booleans as
 * themselves. Unlike a TOML file, the string {@code scNull} stays a string.
 *
 * <p>What the decoder does not model is refused rather than read some other way: a text that is not
 * strict JSON, an object that repeats a key, a string with an escape, and a document whose root is
 * not an object.
 */
final class ClientJson {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private ClientJson() {}

  /**
   * The document of a JSON text.
   *
   * @throws IllegalArgumentException when the text is a form the decoder does not read
   */
  static Map<String, Object> read(String text) {
    if (text.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(
          "a JSON text with an escape, which the decoder does not read");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(text);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(
          "not JSON the decoder reads: " + e.getOriginalMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("a JSON document whose root is not an object");
    }
    return object(root);
  }

  private static Map<String, Object> object(JsonNode node) {
    Map<String, Object> out = new LinkedHashMap<>();
    node.properties().forEach(e -> out.put(e.getKey(), value(e.getValue())));
    return out;
  }

  private static Object value(JsonNode node) {
    if (node.isObject()) {
      return object(node);
    }
    if (node.isArray()) {
      List<Object> out = new ArrayList<>();
      node.forEach(n -> out.add(value(n)));
      return out;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber()) {
      return node.bigIntegerValue().longValue(); // the low 64 bits
    }
    if (node.isFloatingPointNumber()) {
      return (double) (float) node.doubleValue();
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    if (node.isNull()) {
      return null;
    }
    throw new IllegalArgumentException("a JSON value the decoder does not read: " + node);
  }
}
