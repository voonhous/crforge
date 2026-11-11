/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.crforge.core.battle.data.GameTables;

/**
 * Hand-written rows for the tests of a champion an action spawns: a unit with a player-triggered
 * ability that enters the battle through an action, not through its own play. Two cards bring it:
 *
 * <ul>
 *   <li>{@link #CARRIER_CARD}, a troop card whose hero form summons {@link #CARRIER}, a walker
 *       whose death action spawns the champion where it fell;
 *   <li>{@link #DROP_CARD}, a spell card whose hero form throws {@link #DROP_PROJECTILE}, whose end
 *       spawns the champion where it lands.
 * </ul>
 *
 * <p>Each hero form links the champion ({@code LinkedChampionCharacter}), so a deck with the card
 * in its hero slot gives the king's first champion slot the champion before any play. Every other
 * card of the decks is one of eight filler cards, each a plain walker, so no test of them leans on
 * a shipped card. Every row is new: the configured tables are copied and the rows added, none
 * altered.
 */
public final class SpawnedChampionTables {

  /** The champion: a character whose only way into the battle is an action. */
  public static final String CHAMPION = "Test_Spawned_Champion";

  /** The champion's ability row. */
  public static final String ABILITY = "Test_Spawned_Champion_Ability";

  /** The ability's cost, in whole elixir. */
  public static final int ABILITY_COST = 1;

  /** How long the champion casts its ability, in milliseconds. */
  public static final int CAST_TIME_MS = 500;

  /** How long into the cast its activation fires, in milliseconds, shorter than the cast. */
  public static final int TRIGGER_DELAY_MS = 100;

  /**
   * The ability's activation, which adds 1 to {@link GameData#TEST_VARIABLE} on the champion: each
   * activation counted where a test reads it.
   */
  public static final String ACTIVATION = "Test_Spawned_Champion_Activation";

  /** The walker whose death action spawns the champion. */
  public static final String CARRIER = "Test_Champion_Carrier";

  /** The carrier's death action, which spawns the champion at the carrier's point. */
  public static final String PLANT = "Test_Champion_Carrier_Plant";

  /** The troop card of the carrier, whose hero form links the champion. */
  public static final String CARRIER_CARD = "Test_Champion_Carrier_Card";

  /** The projectile whose end spawns the champion. */
  public static final String DROP_PROJECTILE = "Test_Champion_Drop_Projectile";

  /** The spell card that throws the projectile, whose hero form links the champion. */
  public static final String DROP_CARD = "Test_Champion_Drop_Card";

  /** The plain walker of the filler cards. */
  public static final String FILLER = "Test_Champion_Filler";

  /**
   * The filler cards, which fill every other deck slot: eight of them, as a deck may hold a card
   * only once.
   */
  private static final List<String> FILLER_CARDS =
      IntStream.rangeClosed(1, 8).mapToObj(n -> "Test_Champion_Filler_Card_" + n).toList();

  /** Every written card's cost, in whole elixir. */
  public static final int CARD_COST = 1;

  private SpawnedChampionTables() {
    // Utility class
  }

  /**
   * The configured tables copied into a folder with the rows written.
   *
   * @param folder the folder to copy them into
   * @return the tables with the rows
   */
  public static GameTables write(Path folder) throws IOException {
    writeRows(folder);
    return GameTables.load(folder);
  }

  /**
   * Copies the configured tables into a folder and writes the rows, for a fixture that writes more
   * rows of its own before it loads the folder.
   *
   * @param folder the folder to copy them into
   */
  public static void writeRows(Path folder) throws IOException {
    GameData.altered(folder, "characters", SpawnedChampionTables::writeCharacters);
    GameData.alterLoaded(folder, "character_abilities", SpawnedChampionTables::writeAbility);
    GameData.alterLoaded(folder, "actions", SpawnedChampionTables::writeActions);
    GameData.addTestVariable(folder);
    GameData.alterLoaded(folder, "projectiles", SpawnedChampionTables::writeProjectile);
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> {
          WrittenRows.card(rows, CARRIER_CARD, CARD_COST)
              .put("SummonCharacter", CARRIER)
              .put("ForceToStartingHand", true);
          for (String filler : FILLER_CARDS) {
            WrittenRows.card(rows, filler, CARD_COST).put("SummonCharacter", FILLER);
          }
          WrittenRows.heroForms(rows, CARRIER_CARD);
        });
    GameData.alterLoaded(
        folder,
        "spells_other",
        rows -> {
          WrittenRows.card(rows, DROP_CARD, CARD_COST)
              .put("Projectile", DROP_PROJECTILE)
              .put("ForceToStartingHand", true);
          WrittenRows.heroForms(rows, DROP_CARD);
        });
    GameData.alterLoaded(
        folder,
        "spells_hero_form",
        rows -> {
          WrittenRows.heroForm(rows, CARRIER_CARD, CARD_COST, CHAMPION)
              .put("SummonCharacter", CARRIER);
          WrittenRows.heroForm(rows, DROP_CARD, CARD_COST, CHAMPION)
              .put("Projectile", DROP_PROJECTILE);
        });
  }

  /**
   * A deck of a written card first and seven filler cards.
   *
   * @param card the first card
   */
  public static List<String> deck(String card) {
    List<String> deck = new ArrayList<>();
    deck.add(card);
    deck.addAll(FILLER_CARDS.subList(0, 7));
    return deck;
  }

  /** A deck of the eight filler cards. */
  public static List<String> fillers() {
    return FILLER_CARDS;
  }

  /** The hero form row of a written card. */
  public static String heroForm(String card) {
    return WrittenRows.heroForm(card);
  }

  /** The champion, the carrier and the filler, each a plain walker on rows of its own. */
  private static void writeCharacters(ObjectNode rows) {
    WrittenRows.walker(rows, CHAMPION).put("Ability", ABILITY);
    WrittenRows.walker(rows, CARRIER).put("OnDeathAction", PLANT);
    WrittenRows.walker(rows, FILLER);
  }

  /** The champion's ability: a cast with a cost, its activation counted as it fires. */
  private static void writeAbility(ObjectNode rows) {
    WrittenRows.row(rows, ABILITY, "CharacterAbility")
        .put("Name", ABILITY)
        .put("IsChampion", true)
        .put("ManaCost", ABILITY_COST)
        .put("CastTime", CAST_TIME_MS)
        .put("TriggerDelay", TRIGGER_DELAY_MS)
        .put("OnActivationAction", ACTIVATION);
  }

  /**
   * The carrier's death action, a spawn of the champion at the dying carrier's point, and the
   * ability's activation, which counts itself in {@link GameData#TEST_VARIABLE}.
   */
  private static void writeActions(ObjectNode rows) {
    WrittenRows.action(rows, PLANT, "ActionSpawnToLocation")
        .put("SpawnData", CHAMPION)
        .put("SpawnType", "CharacterType");
    WrittenRows.action(rows, ACTIVATION, "ActionSetVariable")
        .put("Variable", GameData.TEST_VARIABLE)
        .put("Value", GameData.TEST_VARIABLE + " + 1");
  }

  /** The thrown projectile, which deals nothing and spawns the champion where it ends. */
  private static void writeProjectile(ObjectNode rows) {
    WrittenRows.row(rows, DROP_PROJECTILE, "Projectile")
        .put("Name", DROP_PROJECTILE)
        .put("Rarity", "Common")
        .put("Speed", 500)
        .put("SpawnCharacter", CHAMPION);
  }
}
