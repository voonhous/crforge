/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/**
 * Helpers that write new rows into the rows of a table, for the fixtures that hand-write the rows a
 * test needs ({@link SpawnedChampionTables}, {@link HeroFormTables}): each adds a row after the
 * table's last one, in the table's own layout, and answers its columns (an action row its fields)
 * to add to. No helper alters a row the table already holds.
 */
public final class WrittenRows {

  /** The first global id a written character may take, the first free one from it on. */
  private static final int FIRST_GLOBAL_ID = 34_900_000;

  /** The suffix of a card's hero form row. */
  private static final String HERO = "_hero";

  private WrittenRows() {
    // Utility class
  }

  /**
   * A new row of a table of a row class, its columns to add to.
   *
   * @param rows the table's rows
   * @param name the row's name
   * @param type the row's class, without its Logic prefix and Data suffix
   */
  public static ObjectNode row(ObjectNode rows, String name, String type) {
    ObjectNode row = rows.putObject(name);
    row.put("index", rows.size() - 1);
    row.put("class", "Logic" + type + "Data");
    return row.putObject("columns");
  }

  /**
   * A character row with only its name and rarity, with the first free global id from {@link
   * #FIRST_GLOBAL_ID} on, its columns to add to.
   *
   * @param rows the characters table's rows
   * @param name the row's name
   */
  public static ObjectNode character(ObjectNode rows, String name) {
    Set<Integer> taken = new HashSet<>();
    for (JsonNode row : rows) {
      if (row.has("global_id")) {
        taken.add(row.get("global_id").asInt());
      }
    }
    int globalId = FIRST_GLOBAL_ID;
    while (taken.contains(globalId)) {
      globalId++;
    }
    ObjectNode row = rows.putObject(name);
    row.put("index", rows.size() - 1);
    row.put("class", "LogicCharacterData");
    row.put("global_id", globalId);
    return row.putObject("columns").put("Name", name).put("Rarity", "Common");
  }

  /**
   * A character row of a plain melee walker ({@link #character}), its columns to add to.
   *
   * @param rows the characters table's rows
   * @param name the row's name
   */
  public static ObjectNode walker(ObjectNode rows, String name) {
    return character(rows, name)
        .put("Hitpoints", 1000)
        .put("Damage", 10)
        .put("HitSpeed", 1000)
        .put("LoadTime", 500)
        .put("Range", 500)
        .put("SightRange", 5500)
        .put("Speed", 60)
        .put("CollisionRadius", 500)
        .put("Mass", 4)
        .put("DeployTime", 1000)
        .put("AttacksGround", true);
  }

  /**
   * An action row of a class, its fields to fill.
   *
   * @param rows the actions table's rows
   * @param name the action's name
   * @param type the action's class type, such as ActionGroup
   */
  public static ObjectNode action(ObjectNode rows, String name, String type) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + type + "Data");
    row.put("ClassType", type);
    return row.putObject("fields").put("ClassType", type);
  }

  /**
   * An action group row: its sub-actions in order, each started its delay after the group starts.
   *
   * @param rows the actions table's rows
   * @param name the group's name
   * @param delaysMs each sub-action's delay from the group's start, in milliseconds
   * @param subActions the sub-actions' names, as many as the delays
   */
  public static ObjectNode group(
      ObjectNode rows, String name, int[] delaysMs, String... subActions) {
    ObjectNode fields = action(rows, name, "ActionGroup");
    ArrayNode listed = fields.putArray("SubActions");
    for (String subAction : subActions) {
      listed.addObject().put("action", subAction);
    }
    ArrayNode delays = fields.putArray("SubActionsDelay");
    for (int delay : delaysMs) {
      delays.add(delay);
    }
    return fields;
  }

  /**
   * A card row of a cost, its columns to add to.
   *
   * @param rows the rows of a card table (spells_characters, spells_buildings or spells_other)
   * @param name the card's name
   * @param cost its cost, in whole elixir
   */
  public static ObjectNode card(ObjectNode rows, String name, int cost) {
    return row(rows, name, "Spell").put("Name", name).put("Rarity", "Common").put("ManaCost", cost);
  }

  /** The hero form row of a written card. */
  public static String heroForm(String card) {
    return card + HERO;
  }

  /**
   * Lists a written card's hero form as its only other form.
   *
   * @param rows the rows of the card's table
   * @param card the card's name
   */
  public static void heroForms(ObjectNode rows, String card) {
    GameData.columns(rows, card).putArray("EvolvedSpells").add(heroForm(card));
  }

  /**
   * The hero form row of a card, linking a champion, its columns to add to.
   *
   * @param rows the hero form table's rows
   * @param card the card's name
   * @param cost the hero form's cost, in whole elixir
   * @param champion the character the hero form links as its champion
   */
  public static ObjectNode heroForm(ObjectNode rows, String card, int cost, String champion) {
    return row(rows, heroForm(card), "SpellHeroForm")
        .put("Name", heroForm(card))
        .put("Rarity", "Common")
        .put("CardForm", "HeroForm")
        .put("ManaCost", cost)
        .put("LinkedChampionCharacter", champion);
  }

  /**
   * A variable row with a default value.
   *
   * @param rows the variables table's rows
   * @param name the variable's name
   * @param defaultValue the value it starts at
   */
  public static void variable(ObjectNode rows, String name, int defaultValue) {
    row(rows, name, "Variable").put("DefaultValue", defaultValue);
  }

  /**
   * A card group row listing a card and its hero form, as a hero's listener names its group.
   *
   * @param rows the card groups table's rows
   * @param name the group's name
   * @param card the card the group plays, whose hero form it lists among its heroes
   */
  public static void cardGroup(ObjectNode rows, String name, String card) {
    ObjectNode columns = row(rows, name, "CardGroup");
    columns.putArray("Heroes").add(heroForm(card));
    columns.putArray("PlayableCards").add(card);
    columns.putArray("SupportCards");
  }
}
