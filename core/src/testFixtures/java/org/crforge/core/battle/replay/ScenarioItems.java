/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;

/**
 * The packed items of a test scenario's plays as the player's client builds them from the tables:
 * the cost and the level field a deck card carries are its rows' (ManaCost, the rarity's
 * RelativeLevel), never literals, so a scenario stays one its deck can carry on any version of the
 * tables. Every value is read from the row's own columns, not through the battle's readers.
 *
 * <p>The scenarios of {@link Scenarios} pack the items of the tables they were written against; a
 * test fits them to the configured tables before it reads or runs them.
 *
 * <p>Item layout: the evolution field in bits 0..3, the option field 4..6, the count field 7..9,
 * the level field 10..16, the cosmetic field 17..18, the slot flags 19..21, the deck index field
 * 22..27 and the cost from bit 28.
 */
public final class ScenarioItems {

  private static final int LEVEL_SHIFT = 10;
  private static final int LEVEL_MASK = 0x7f;
  private static final int OPTION_SHIFT = 4;
  private static final int OPTION_MASK = 0x7;
  private static final int COUNT_SHIFT = 7;
  private static final int COUNT_MASK = 0x7;
  private static final int INDEX_SHIFT = 22;
  private static final int INDEX_MASK = 0x3f;
  private static final int COST_SHIFT = 28;
  private static final int FIELD_MASK = 0xf;

  private ScenarioItems() {
    // Utility class
  }

  /**
   * The scenario with each play's item fitted to the tables, in place: its cost and level field
   * those its deck card's rows give, and for an evolution slot's card the evolution field its count
   * gives against the evolved row's DarkElixirCost. Every other field is kept as written.
   */
  public static ObjectNode fitted(ObjectNode scenario, GameTables tables) {
    for (JsonNode command : scenario.path("cmd")) {
      JsonNode sel = command.path("c").path("sel");
      if (sel.isObject()) {
        ((ObjectNode) sel).put("pd", item(scenario, command.path("c"), tables));
      }
    }
    return scenario;
  }

  /** The item a play of the scenario carries, fitted to the tables. */
  private static int item(ObjectNode scenario, JsonNode body, GameTables tables) {
    JsonNode sel = body.path("sel");
    int packed = sel.path("pd").asInt();
    GameRow card = row(tables, sel.path("os").asInt());
    int side = side(scenario, body.path("idLo").asInt());
    int deckIndex = ((packed >>> INDEX_SHIFT) & INDEX_MASK) - 1;
    int levelIndex =
        scenario.path("battle").path("deck" + side).path("sp").get(deckIndex).path("l").asInt();
    int levelField = levelField(tables, card, levelIndex);
    int cost;
    if (flag(card, "Mirror")) {
      // The Mirror repeats a card one MIRROR_LEVEL_OFFSET above its own level, for its own cost
      // plus the card's, at most the most elixir there can be.
      levelField = Math.max(levelField + global(tables, "MIRROR_LEVEL_OFFSET"), 0);
      GameRow repeated = row(tables, sel.path("fs").asInt());
      cost = Math.min(cost(card) + cost(repeated), global(tables, "MAX_MANA"));
    } else if (!text(card, "CustomClassType").isEmpty()) {
      // A variant card's cost is its option's, the option field the option's index plus 1.
      int option = (packed >>> OPTION_SHIFT) & OPTION_MASK;
      cost = cost(optionRow(tables, card, option - 1));
    } else {
      int count = (packed >>> COUNT_SHIFT) & COUNT_MASK;
      if (count > 0) {
        // An evolution slot's card carries its count plus 1; it is evolved once the count reaches
        // its evolved row's DarkElixirCost.
        GameRow evolved = evolvedRow(tables, card);
        int darkElixirCost = number(evolved, "DarkElixirCost");
        boolean evolution = darkElixirCost >= 1 && count - 1 >= darkElixirCost;
        packed = (packed & ~FIELD_MASK) | (evolution ? 1 : 0);
        cost = cost(evolution ? evolved : card);
      } else {
        cost = cost(card);
      }
    }
    return withCost(withLevelField(packed, levelField), cost);
  }

  /** An item with its level field replaced. */
  public static int withLevelField(int item, int levelField) {
    return (item & ~(LEVEL_MASK << LEVEL_SHIFT)) | ((levelField & LEVEL_MASK) << LEVEL_SHIFT);
  }

  /** An item with its cost replaced. */
  public static int withCost(int item, int cost) {
    return (item & ~(-1 << COST_SHIFT)) | (cost << COST_SHIFT);
  }

  /** An item's cost. */
  public static int costOf(int item) {
    return item >>> COST_SHIFT;
  }

  /** An item's level field. */
  public static int levelFieldOf(int item) {
    return (item >>> LEVEL_SHIFT) & LEVEL_MASK;
  }

  /** A card's level field at a level index: the index plus its rarity's RelativeLevel. */
  public static int levelField(GameTables tables, GameRow card, int levelIndex) {
    return levelIndex + relativeLevel(tables, "rarities", text(card, "Rarity"));
  }

  /** A rarity's RelativeLevel, from the rarities or the support_rarities table. */
  public static int relativeLevel(GameTables tables, String table, String rarity) {
    return number(tables.table(table).row(rarity), "RelativeLevel");
  }

  /** A card's cost: its row's ManaCost. */
  public static int cost(GameRow card) {
    return number(card, "ManaCost");
  }

  /** A card's cost by its row name, from whichever card table holds it. */
  public static int cost(GameTables tables, String card) {
    return cost(card(tables, card));
  }

  /** A card row by name, from whichever card table holds it. */
  public static GameRow card(GameTables tables, String name) {
    for (String table :
        new String[] {"spells_characters", "spells_buildings", "spells_other", "spells_evolved"}) {
      if (tables.table(table).has(name)) {
        return tables.table(table).row(name);
      }
    }
    throw new IllegalArgumentException("no card row " + name);
  }

  /** A variant card's option row: the card row its Options entry names as its SpellData. */
  public static GameRow optionRow(GameTables tables, GameRow card, int option) {
    return card(tables, card.columns().get("Options").get(option).path("SpellData").asText());
  }

  /** The evolved row of a card: the first of its EvolvedSpells the evolved table holds. */
  public static GameRow evolvedRow(GameTables tables, GameRow card) {
    GameTable evolved = tables.table("spells_evolved");
    for (JsonNode name : card.columns().get("EvolvedSpells")) {
      if (evolved.has(name.asText())) {
        return evolved.row(name.asText());
      }
    }
    throw new IllegalArgumentException(card.name() + " has no evolved row");
  }

  /** A global's NumberValue. */
  public static int global(GameTables tables, String name) {
    return number(tables.table("globals").row(name), "NumberValue");
  }

  /** The row a data id names: its table's id times a million plus the row's index. */
  public static GameRow row(GameTables tables, int id) {
    String tableId = Integer.toString(id / 1_000_000);
    for (String name : tables.tableNames()) {
      GameTable table = tables.table(name);
      if (table.id().equals(tableId)) {
        for (GameRow row : table.rows()) {
          if (row.index() == id % 1_000_000) {
            return row;
          }
        }
      }
    }
    throw new IllegalArgumentException("no row of id " + id);
  }

  /** The side whose avatar holds an account's low word. */
  private static int side(ObjectNode scenario, int accountLo) {
    for (int side = 0; side < 2; side++) {
      if (scenario.path("battle").path("avatar" + side).path("accountID.lo").asInt() == accountLo) {
        return side;
      }
    }
    throw new IllegalArgumentException("no avatar of account " + accountLo);
  }

  public static int number(GameRow row, String column) {
    JsonNode value = row.columns().get(column);
    return value == null || value.isNull() ? 0 : value.asInt();
  }

  private static boolean flag(GameRow row, String column) {
    JsonNode value = row.columns().get(column);
    return value != null && value.asBoolean();
  }

  private static String text(GameRow row, String column) {
    JsonNode value = row.columns().get(column);
    return value == null || value.isNull() ? "" : value.asText();
  }
}
