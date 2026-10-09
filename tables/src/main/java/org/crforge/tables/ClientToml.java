package org.crforge.tables;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a TOML file of the game's data as the client's parser does: standard TOML, plus the one
 * rule of the client's grammar the data has needed beyond it, a repeated {@code [A]} header.
 *
 * <p>The client accepts the same {@code [A]} header twice and merges the two bodies, which TOML
 * forbids. Only when a document fails to read and repeats a plain table header line is it merged:
 * the body of each repeat (its lines up to the next header line) moves to the end of the first
 * occurrence's body, and the repeat's header line is dropped, so the table holds the first body's
 * keys, then the repeat's. A key both bodies set is still refused. Array-of-tables headers ({@code
 * [[A]]}) and the sub-table headers after them are left alone: repeating those is TOML. A document
 * that reads as it stands is never touched.
 *
 * <p>Values come out as the decoder holds them: tables as {@link LinkedHashMap} in file order,
 * arrays as {@link List}, integers as {@link Long} (a larger one as {@link java.math.BigInteger}),
 * floats as {@link Double}, and strings and booleans as themselves.
 */
final class ClientToml {

  private static final TomlMapper MAPPER = new TomlMapper();

  /** A plain {@code [A]} header line, not {@code [[A]]}. */
  private static final Pattern HEADER =
      Pattern.compile(
          "^\\s*\\[\\s*([^\\[\\]]+?)\\s*\\]\\s*(#.*)?$", Pattern.UNICODE_CHARACTER_CLASS);

  /** Any header line. */
  private static final Pattern ANY_HEADER =
      Pattern.compile("^\\s*\\[", Pattern.UNICODE_CHARACTER_CLASS);

  /** An array-of-tables header line. */
  private static final Pattern ARRAY_HEADER =
      Pattern.compile("^\\s*\\[\\[\\s*([^\\[\\]]+?)\\s*\\]\\]", Pattern.UNICODE_CHARACTER_CLASS);

  private ClientToml() {}

  /**
   * The document of a TOML text.
   *
   * @throws IllegalArgumentException when the text is not TOML, with the client's merge or without
   */
  static Map<String, Object> read(String text) {
    try {
      return table(MAPPER.readTree(text));
    } catch (JacksonException e) {
      String merged = mergeRepeatedHeaders(text);
      if (merged == null) {
        throw new IllegalArgumentException("not TOML: " + e.getOriginalMessage(), e);
      }
      try {
        return table(MAPPER.readTree(merged));
      } catch (JacksonException again) {
        throw new IllegalArgumentException("not TOML: " + again.getOriginalMessage(), again);
      }
    }
  }

  /**
   * The text with every repeated plain header's body moved under its first occurrence, or null when
   * no plain header outside an array of tables repeats.
   */
  static String mergeRepeatedHeaders(String text) {
    List<Block> blocks = new ArrayList<>();
    Block current = new Block(null, null); // the preamble is a block without a header
    for (String line : text.split("\n", -1)) {
      if (ANY_HEADER.matcher(line).find()) {
        blocks.add(current);
        Matcher m = HEADER.matcher(line);
        current = new Block(line, m.matches() ? m.group(1) : null);
      } else {
        current.lines.add(line);
      }
    }
    blocks.add(current);
    // a sub-table header after [[A]] belongs to that array element; repeating it per element is
    // TOML, so only names outside every array-of-tables path count
    Set<String> arrays = new HashSet<>();
    for (Block b : blocks) {
      if (b.header != null) {
        Matcher m = ARRAY_HEADER.matcher(b.header);
        if (m.lookingAt()) {
          arrays.add(m.group(1).strip());
        }
      }
    }
    Map<String, Block> first = new HashMap<>();
    List<Block> out = new ArrayList<>();
    boolean repeats = false;
    for (Block b : blocks) {
      if (b.name != null && !inArray(b.name, arrays)) {
        Block earlier = first.get(b.name);
        if (earlier != null) {
          earlier.lines.addAll(b.lines);
          repeats = true;
          continue;
        }
        first.put(b.name, b);
      }
      out.add(b);
    }
    if (!repeats) {
      return null;
    }
    List<String> lines = new ArrayList<>();
    for (Block b : out) {
      if (b.header != null) {
        lines.add(b.header);
      }
      lines.addAll(b.lines);
    }
    return String.join("\n", lines);
  }

  private static boolean inArray(String name, Set<String> arrays) {
    for (String a : arrays) {
      if (name.equals(a) || name.startsWith(a + ".")) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> table(JsonNode node) {
    Map<String, Object> out = new LinkedHashMap<>();
    node.properties().forEach(e -> out.put(e.getKey(), value(e.getValue())));
    return out;
  }

  private static Object value(JsonNode node) {
    if (node.isObject()) {
      return table(node);
    }
    if (node.isArray()) {
      List<Object> out = new ArrayList<>();
      node.forEach(n -> out.add(value(n)));
      return out;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isBigInteger()) {
      return node.bigIntegerValue();
    }
    if (node.isIntegralNumber()) {
      return node.longValue();
    }
    if (node.isFloatingPointNumber()) {
      return node.doubleValue();
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    throw new IllegalArgumentException("a TOML value the decoder does not read: " + node);
  }

  private static final class Block {
    final String header;
    final String name;
    final List<String> lines = new ArrayList<>();

    Block(String header, String name) {
      this.header = header;
      this.name = name;
    }
  }
}
