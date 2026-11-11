/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.data.GameTables;

/**
 * Hand-written cards of the kinds a deck holds beside plain troops, on {@link
 * SpawnedChampionTables}' rows (whose filler cards fill the decks and whose champion the champion
 * card summons):
 *
 * <ul>
 *   <li>{@link #CHAMPION_CARD}, a troop card that summons its champion, {@link #CARD_CHAMPION},
 *       whose ability has a cooldown;
 *   <li>{@link #EVOLVING_CARD}, a troop card with an evolved form, {@link #EVOLVED_FORM}, which
 *       takes {@link #EVOLUTION_COUNT} plays to charge;
 *   <li>{@link #MIRROR_CARD}, a Mirror, which repeats its side's last card one level up;
 *   <li>{@link #SPELL_CARD}, a spell card that throws {@link #SPELL_PROJECTILE} at the point;
 *   <li>{@link #VARIANT_CARD}, a variant card played as one of two options by the elixir its king
 *       holds: {@link #VARIANT_HIGH} from {@link #VARIANT_HIGH_COST}, else {@link #VARIANT_LOW}.
 * </ul>
 *
 * <p>Every row is new: the configured tables are copied and the rows added, none altered.
 */
public final class CardKindTables {

  /** Every plain written card's cost, in whole elixir: the filler cards' cost. */
  public static final int CARD_COST = SpawnedChampionTables.CARD_COST;

  /** A troop card that summons its champion by its own play. */
  public static final String CHAMPION_CARD = "Test_Champion_Card";

  /** The champion card's champion: a walker with an ability. */
  public static final String CARD_CHAMPION = "Test_Card_Champion";

  /** The card champion's ability row. */
  public static final String CARD_CHAMPION_ABILITY = "Test_Card_Champion_Ability";

  /** How long after a use the card champion's ability refuses the next, in milliseconds. */
  public static final int CARD_CHAMPION_COOLDOWN_MS = 10000;

  /** A troop card with an evolved form. */
  public static final String EVOLVING_CARD = "Test_Evolving_Card";

  /** The evolved form of {@link #EVOLVING_CARD}. */
  public static final String EVOLVED_FORM = "Test_Evolving_Card_EV1";

  /** The plays of the evolving card that charge its evolution. */
  public static final int EVOLUTION_COUNT = 2;

  /** The Mirror. */
  public static final String MIRROR_CARD = "Test_Mirror_Card";

  /** A spell card. */
  public static final String SPELL_CARD = "Test_Spell_Card";

  /** The spell card's projectile, which deals a little damage where it lands. */
  public static final String SPELL_PROJECTILE = "Test_Spell_Projectile";

  /** The variant card. */
  public static final String VARIANT_CARD = "Test_Variant_Card";

  /** The option played with the king's elixir at {@link #VARIANT_HIGH_COST} or more. */
  public static final String VARIANT_HIGH = "Test_Variant_High";

  /** The option played with less. */
  public static final String VARIANT_LOW = "Test_Variant_Low";

  /** The high option's cost, in whole elixir, which is also its elixir trigger. */
  public static final int VARIANT_HIGH_COST = 4;

  /** The low option's cost, in whole elixir, which is also its elixir trigger. */
  public static final int VARIANT_LOW_COST = 2;

  /** An option's trigger, in thousandths of an elixir, as the variant row writes it. */
  private static final int TRIGGER_PER_ELIXIR = 1000;

  private CardKindTables() {
    // Utility class
  }

  /**
   * The configured tables copied into a folder with {@link SpawnedChampionTables}' rows and these
   * written.
   *
   * @param folder the folder to copy them into
   * @return the tables with the rows
   */
  public static GameTables write(Path folder) throws IOException {
    SpawnedChampionTables.writeRows(folder);
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            WrittenRows.walker(rows, CARD_CHAMPION)
                .put("Hitpoints", 3000)
                .put("Ability", CARD_CHAMPION_ABILITY));
    // A cast whose activation is the spawned champion's: it counts itself in a test variable.
    GameData.alterLoaded(
        folder,
        "character_abilities",
        rows ->
            WrittenRows.row(rows, CARD_CHAMPION_ABILITY, "CharacterAbility")
                .put("Name", CARD_CHAMPION_ABILITY)
                .put("IsChampion", true)
                .put("ManaCost", 1)
                .put("Cooldown", CARD_CHAMPION_COOLDOWN_MS)
                .put("CastTime", SpawnedChampionTables.CAST_TIME_MS)
                .put("TriggerDelay", SpawnedChampionTables.TRIGGER_DELAY_MS)
                .put("OnActivationAction", SpawnedChampionTables.ACTIVATION));
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> {
          WrittenRows.card(rows, CHAMPION_CARD, CARD_COST).put("SummonCharacter", CARD_CHAMPION);
          WrittenRows.card(rows, EVOLVING_CARD, CARD_COST)
              .put("SummonCharacter", SpawnedChampionTables.FILLER)
              .putArray("EvolvedSpells")
              .add(EVOLVED_FORM);
          WrittenRows.card(rows, VARIANT_HIGH, VARIANT_HIGH_COST)
              .put("SummonCharacter", SpawnedChampionTables.FILLER)
              .put("NotVisible", true);
          WrittenRows.card(rows, VARIANT_LOW, VARIANT_LOW_COST)
              .put("SummonCharacter", SpawnedChampionTables.FILLER)
              .put("NotVisible", true);
        });
    GameData.alterLoaded(
        folder,
        "spells_evolved",
        rows ->
            WrittenRows.row(rows, EVOLVED_FORM, "Spell")
                .put("Name", EVOLVED_FORM)
                .put("Rarity", "Common")
                .put("ManaCost", CARD_COST)
                .put("DarkElixirCost", EVOLUTION_COUNT)
                .put("SummonCharacter", SpawnedChampionTables.FILLER)
                .put("SummonNumber", 1));
    GameData.alterLoaded(
        folder,
        "spells_other",
        rows -> {
          WrittenRows.card(rows, MIRROR_CARD, CARD_COST).put("Mirror", true);
          WrittenRows.card(rows, SPELL_CARD, CARD_COST).put("Projectile", SPELL_PROJECTILE);
          ObjectNode variant =
              WrittenRows.card(rows, VARIANT_CARD, VARIANT_HIGH_COST)
                  .put("CustomClassType", "LogicBattleSpellVariantData")
                  .put("UseProjectedTimeSummon", true)
                  .put("SummonNumber", 1);
          ArrayNode options = variant.putArray("Options");
          option(options, VARIANT_HIGH, VARIANT_HIGH_COST);
          option(options, VARIANT_LOW, VARIANT_LOW_COST);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            WrittenRows.row(rows, SPELL_PROJECTILE, "Projectile")
                .put("Name", SPELL_PROJECTILE)
                .put("Rarity", "Common")
                .put("Speed", 500)
                .put("Damage", 10)
                .put("Radius", 1000));
    return GameTables.load(folder);
  }

  /** A variant option of a card, played from its cost in the king's elixir. */
  private static void option(ArrayNode options, String spell, int cost) {
    options
        .addObject()
        .put("AvailableManaTrigger", cost * TRIGGER_PER_ELIXIR)
        .put("PrecastPendingTime", 1200)
        .put("SpellData", spell);
  }

  /**
   * A deck of written cards first and filler cards after them, eight in all.
   *
   * @param cards the first cards
   */
  public static List<String> deck(String... cards) {
    List<String> deck = new ArrayList<>(List.of(cards));
    deck.addAll(SpawnedChampionTables.fillers().subList(0, 8 - cards.length));
    return deck;
  }
}
