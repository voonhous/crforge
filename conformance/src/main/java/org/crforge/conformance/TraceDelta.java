/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The delta lines of a trace (format 1): each observation written as its change from the one
 * before, which a trace's observations repeat almost whole from tick to tick.
 *
 * <p>The delta file has one line per observation. Line 1 is the first observation as is. Every
 * later line is the delta of its observation against the one before, {@code {}} when the two are
 * equal. A delta is read against the old value:
 *
 * <ul>
 *   <li>an integer against an integer: the difference, new minus old;
 *   <li>an object against an object: the keys that changed or are new, each value read against the
 *       old value of its key (a new key's value is taken as is), and {@code "-"}: the keys removed;
 *   <li>an object against a list: with {@code "*"}, the delta of each element in turn ({@code {}}
 *       for an element that did not change), the list keeping its length; else the change of a list
 *       of objects each with an integer {@code "id"}, in ascending id: {@code "-"} the ids removed,
 *       {@code "+"} the objects added, whole, {@code "~"} for each kept object that changed, its id
 *       ({@code "id"}) and its delta; the result holds every kept and added object in ascending id;
 *   <li>anything else: the new value, whole.
 * </ul>
 *
 * <p>In a trace's terms, an observation holds three kinds of container, and each has its own kind
 * of delta:
 *
 * <ul>
 *   <li>an object (key to value), such as the observation itself, an entity or a side: only the
 *       keys that changed, and {@code "-"} for the keys that are gone;
 *   <li>a list of objects each with an {@code "id"}, the entities: {@code "-"} the ids removed,
 *       {@code "+"} the objects added, {@code "~"} the objects changed;
 *   <li>any other list of objects, matched by position, the sides (side 0, side 1): {@code "*"},
 *       one delta per position.
 * </ul>
 *
 * <p>So {@code "-"} has two jobs, removed keys in an object and removed ids in an id list; {@code
 * "+"} and {@code "~"} only appear in an id list, {@code "*"} only in a list matched by position.
 * Entities are matched by id, not position, because units come and go in the middle of the list:
 * after one dies, every later entity moves up a place, and a delta by position would compare the
 * wrong units. The sides never move, so their position is enough.
 *
 * <p>For example, a tick on which one unit appeared, another left, a third moved, and both sides
 * gained elixir (the added unit shortened):
 *
 * <pre>{@code
 * {"entities":{"+":[{"hp":1000,"id":5000040,...,"x":3500,"y":9000}],
 *              "-":[5000031],
 *              "~":[{"id":5000033,"x":-58,"y":17}]},
 *  "sides":{"*":[{"elixir":178},{"elixir":178}]},
 *  "tick":1}
 * }</pre>
 *
 * <p>Read it as: entity 5000040 appeared, whole since it is new; entity 5000031 is gone; entity
 * 5000033 moved, its x down by 58 and its y up by 17; side 0's elixir rose by 178 and so did side
 * 1's; the tick is one more than before. Every key and value not named is as it was.
 *
 * <p>Each observation rebuilt is written as a run writes its trace ({@link
 * ReplaySmokeRun#OBSERVATION_WRITER}): every object's keys sorted, no spaces. A trace is published
 * in this form only when its rebuilt lines are its own bytes, and the comparison checks the rebuilt
 * trace against the reference's {@code trace_sha256} all the same. A delta that does not apply (a
 * removed key or id that is not there, an element count that differs) is refused, never guessed at.
 */
final class TraceDelta {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** In an object delta, the keys removed; in an id list delta, the ids removed. */
  private static final String REMOVED = "-";

  /** In an id list delta, the objects added, whole. */
  private static final String ADDED = "+";

  /** In an id list delta, each kept object that changed: its id and its delta. */
  private static final String CHANGED = "~";

  /** A list delta matched by position: one delta per element, {@code {}} when unchanged. */
  private static final String EACH = "*";

  private static final String ID = "id";

  private TraceDelta() {
    // Static utility
  }

  /**
   * The trace the delta lines give: one rebuilt observation per line, each ending in a newline.
   *
   * @throws IOException when a line is not JSON or its delta does not apply
   */
  static byte[] decode(byte[] delta) throws IOException {
    ByteArrayOutputStream trace = new ByteArrayOutputStream(delta.length * 8);
    JsonNode previous = null;
    int number = 0;
    int start = 0;
    while (start < delta.length) {
      int end = start;
      while (end < delta.length && delta[end] != '\n') {
        end++;
      }
      if (end > start) {
        number++;
        JsonNode line = MAPPER.readTree(delta, start, end - start);
        try {
          previous = previous == null ? line : apply(previous, line);
        } catch (IllegalArgumentException e) {
          throw new IOException("delta line " + number + " does not apply: " + e.getMessage(), e);
        }
        ReplaySmokeRun.OBSERVATION_WRITER.writeValue(trace, previous);
        trace.write('\n');
      }
      start = end + 1;
    }
    return trace.toByteArray();
  }

  /** The value {@code delta} gives against {@code old}; {@code old} is left as it is. */
  static JsonNode apply(JsonNode old, JsonNode delta) {
    if (old.isIntegralNumber() && delta.isIntegralNumber()) {
      if (!old.canConvertToLong() || !delta.canConvertToLong()) {
        throw new IllegalArgumentException("an integer beyond 64 bits");
      }
      return NODES.numberNode(Math.addExact(old.longValue(), delta.longValue()));
    }
    if (old.isObject() && delta.isObject()) {
      return applyToObject((ObjectNode) old, (ObjectNode) delta);
    }
    if (old.isArray() && delta.isObject()) {
      return delta.has(EACH)
          ? applyToEach((ArrayNode) old, (ObjectNode) delta)
          : applyToIds((ArrayNode) old, (ObjectNode) delta);
    }
    return delta;
  }

  private static ObjectNode applyToObject(ObjectNode old, ObjectNode delta) {
    // A shallow copy: the old observation's values are shared, never changed
    ObjectNode result = NODES.objectNode();
    result.setAll(old);
    if (delta.has(REMOVED)) {
      for (JsonNode key : array(delta.get(REMOVED), "the removed keys")) {
        if (result.remove(key.asText()) == null) {
          throw new IllegalArgumentException("removes the key " + key + ", which is not there");
        }
      }
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = delta.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> field = it.next();
      if (field.getKey().equals(REMOVED)) {
        continue;
      }
      JsonNode current = result.get(field.getKey());
      result.set(
          field.getKey(), current == null ? field.getValue() : apply(current, field.getValue()));
    }
    return result;
  }

  private static ArrayNode applyToEach(ArrayNode old, ObjectNode delta) {
    if (delta.size() != 1) {
      throw new IllegalArgumentException("an element-wise list delta with other keys");
    }
    ArrayNode parts = array(delta.get(EACH), "an element-wise list delta");
    if (parts.size() != old.size()) {
      throw new IllegalArgumentException(
          "an element-wise delta of " + parts.size() + " elements against " + old.size());
    }
    ArrayNode result = NODES.arrayNode(old.size());
    for (int i = 0; i < old.size(); i++) {
      result.add(apply(old.get(i), parts.get(i)));
    }
    return result;
  }

  private static ArrayNode applyToIds(ArrayNode old, ObjectNode delta) {
    for (Iterator<String> it = delta.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (!key.equals(REMOVED) && !key.equals(ADDED) && !key.equals(CHANGED)) {
        throw new IllegalArgumentException("an id list delta with the key " + key);
      }
    }
    TreeMap<Long, JsonNode> byId = new TreeMap<>();
    long last = Long.MIN_VALUE;
    for (JsonNode element : old) {
      long id = id(element);
      if (!byId.isEmpty() && id <= last) {
        throw new IllegalArgumentException("an id list delta against ids not ascending");
      }
      byId.put(id, element);
      last = id;
    }
    Set<Long> removed = new HashSet<>();
    if (delta.has(REMOVED)) {
      for (JsonNode id : array(delta.get(REMOVED), "the removed ids")) {
        if (!id.isIntegralNumber() || byId.remove(id.longValue()) == null) {
          throw new IllegalArgumentException("removes the id " + id + ", which is not there");
        }
        removed.add(id.longValue());
      }
    }
    if (delta.has(ADDED)) {
      for (JsonNode element : array(delta.get(ADDED), "the added objects")) {
        long id = id(element);
        if (byId.containsKey(id) || removed.contains(id)) {
          throw new IllegalArgumentException("adds the id " + id + ", which is there");
        }
        byId.put(id, element);
      }
    }
    if (delta.has(CHANGED)) {
      for (JsonNode change : array(delta.get(CHANGED), "the changed objects")) {
        long id = id(change);
        JsonNode element = byId.get(id);
        if (element == null) {
          throw new IllegalArgumentException("changes the id " + id + ", which is not there");
        }
        ObjectNode rest = ((ObjectNode) change).deepCopy();
        rest.remove(ID);
        byId.put(id, apply(element, rest));
      }
    }
    ArrayNode result = NODES.arrayNode(byId.size());
    byId.values().forEach(result::add);
    return result;
  }

  private static long id(JsonNode element) {
    JsonNode id = element.get(ID);
    if (!element.isObject() || id == null || !id.isIntegralNumber() || !id.canConvertToLong()) {
      throw new IllegalArgumentException("an id list element without an integer id");
    }
    return id.longValue();
  }

  private static ArrayNode array(JsonNode node, String what) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException(what + " are not a list");
    }
    return (ArrayNode) node;
  }
}
