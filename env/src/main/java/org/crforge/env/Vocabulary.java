/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;

/**
 * The names an observation refers to by number: every row of the tables the battle reads its cards,
 * characters, buildings, projectiles and area effects from, in one sorted list, so that a card and
 * the character it places share the id of their one name. The ids are fixed by the tables' content:
 * two environments on the same tables number the names the same way.
 *
 * <p>An id is only stable within a data version: a row added or removed by another version moves
 * every later name's id. So the vocabulary is exported with every run, keyed by name, with the data
 * version, the tables' content sha and a hash of the list ({@link #toJson()}); a model trained on
 * one export remaps its ids by name to another, and {@link #checkSame(String)} refuses an export
 * whose ids this vocabulary would read differently.
 */
public final class Vocabulary {

  /** The tables whose row names are numbered, in the order their rows are gathered. */
  static final List<String> TABLES =
      List.of(
          "spells_characters",
          "spells_other",
          "spells_buildings",
          "spells_evolved",
          "spells_hero_form",
          "characters",
          "buildings",
          "projectiles",
          "area_effect_objects");

  /** The tables of the cards a deck holds. */
  static final List<String> DECK_TABLES =
      List.of("spells_characters", "spells_other", "spells_buildings");

  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<String> names;
  private final Map<String, Integer> ids = new HashMap<>();
  private final Set<String> deckCards = new HashSet<>();
  private final String dataVersion;
  private final String contentSha;
  private final String hash;

  /**
   * The vocabulary of a data version's tables.
   *
   * @param tables the game tables
   */
  public Vocabulary(GameTables tables) {
    TreeSet<String> sorted = new TreeSet<>();
    for (String table : TABLES) {
      if (!tables.tableNames().contains(table)) {
        continue;
      }
      for (GameRow row : tables.table(table).rows()) {
        sorted.add(row.name());
      }
    }
    for (String table : DECK_TABLES) {
      for (GameRow row : tables.table(table).rows()) {
        deckCards.add(row.name());
      }
    }
    names = List.copyOf(new ArrayList<>(sorted));
    for (int id = 0; id < names.size(); id++) {
      ids.put(names.get(id), id);
    }
    dataVersion = tables.version();
    contentSha = tables.contentSha();
    hash = hash(names);
  }

  /** The names, by id. */
  public List<String> names() {
    return names;
  }

  /** How many names there are. */
  public int size() {
    return names.size();
  }

  /** The data version of the tables the names come from. */
  public String dataVersion() {
    return dataVersion;
  }

  /** The content sha of the tables the names come from. */
  public String contentSha() {
    return contentSha;
  }

  /** The SHA-256 of the names in id order, one per line: equal hashes number names alike. */
  public String hash() {
    return hash;
  }

  /**
   * The id of a name.
   *
   * @throws IllegalArgumentException for a name of no row
   */
  public int id(String name) {
    Integer id = ids.get(name);
    if (id == null) {
      throw new IllegalArgumentException("the tables have no row named " + name);
    }
    return id;
  }

  /** Whether a name is a card a deck may hold: a row of a card table. */
  public boolean isDeckCard(String name) {
    return deckCards.contains(name);
  }

  /**
   * The vocabulary as JSON: {@code data_version}, {@code content_sha}, {@code hash} and {@code
   * names}, the names by id.
   */
  public String toJson() {
    return toNode().toString();
  }

  /** The vocabulary as a JSON object, for a larger export. */
  ObjectNode toNode() {
    ObjectNode node = JSON.createObjectNode();
    node.put("data_version", dataVersion);
    node.put("content_sha", contentSha);
    node.put("hash", hash);
    ArrayNode list = node.putArray("names");
    names.forEach(list::add);
    return node;
  }

  /**
   * Refuses an exported vocabulary whose ids this one would read differently: a name the two share
   * under different ids, or an id the export gives a name this one does not have. An export of the
   * same tables passes; one of other tables whose ids moved is refused, naming the first moved
   * name, so it is remapped by name rather than read silently shifted.
   *
   * @param exported a vocabulary's {@link #toJson()}, or a larger export holding it under {@code
   *     vocabulary}
   * @throws IllegalArgumentException when the ids differ
   */
  public void checkSame(String exported) {
    JsonNode node;
    try {
      node = JSON.readTree(exported);
    } catch (IOException e) {
      throw new UncheckedIOException("an exported vocabulary that is not JSON", e);
    }
    if (node.has("vocabulary")) {
      node = node.get("vocabulary");
    }
    JsonNode list = node.get("names");
    if (list == null || !list.isArray()) {
      throw new IllegalArgumentException("an exported vocabulary without its names");
    }
    for (int id = 0; id < list.size(); id++) {
      String name = list.get(id).asText();
      Integer own = ids.get(name);
      if (own == null || own != id) {
        throw new IllegalArgumentException(
            "the exported vocabulary ("
                + node.path("data_version").asText()
                + ") numbers "
                + name
                + " "
                + id
                + ", these tables ("
                + dataVersion
                + ") "
                + (own == null ? "have no such row" : "number it " + own)
                + ": remap the ids by name");
      }
    }
    if (list.size() != names.size()) {
      throw new IllegalArgumentException(
          "the exported vocabulary has "
              + list.size()
              + " names, these tables "
              + names.size()
              + ": remap the ids by name");
    }
  }

  private static String hash(List<String> names) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String name : names) {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("no SHA-256 in this JVM", e);
    }
  }
}
