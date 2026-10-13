/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tiebreaker's clearing's kill as the damage drain deals it. The clearing queues the kill with
 * the unit's hit points as they are when it reaches the unit, and the drain hands it to the damage
 * entry every queued hit passes: the entry lowers it by the unit's damage reduction, floored at 1.
 * So a unit with a reduction listed lives through the kill, and is reached again by the next step's
 * clearing, and a unit healed between the clearing and the drain keeps the heal.
 *
 * <p>The buffs are hand-written on rows of the configured tables: one with only a damage reduction,
 * under a protection cap of 100, and one with only a heal over time that may heal past the maximum.
 */
class ClearingKillEntryTest {

  /** The reduction of the hand-written buff, in percent. */
  private static final int REDUCTION = 60;

  /** The row the reduction is written on. */
  private static final String REDUCTION_BUFF = "Knight_Fortify_EV1";

  /** The heal over time of the hand-written heal buff, a hit each step. */
  private static final int HEAL_PER_SECOND = 600;

  /** The row the heal is written on. */
  private static final String HEAL_BUFF = "BatsEV1_Heal";

  private static final List<String> DECK =
      List.of(
          "Knight",
          "Archer",
          "Giant",
          "MiniPekka",
          "Musketeer",
          "Valkyrie",
          "Barbarians",
          "Minions");

  @TempDir Path folder;

  @Test
  @DisplayName(
      "the clearing's kill is lowered by the unit's damage reduction at the damage entry, so the"
          + " unit lives through it and each step's clearing takes the same share again")
  void theClearingKillTakesTheReduction() throws IOException {
    GameTables tables = written();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    battle.startLadderMatch(DECK, DECK, 0, 0);
    int timeUp = timeUpTick();
    // Side 0's Knight walks up its lane, out of every tower's reach, as the time is up.
    CharacterEntity knight = battle.deploy(timeUp - 40, records.unit("Knight"), 11, 0, 3500, 12000);
    stepTo(battle, timeUp + 1);
    knight.getBuffs().apply(records.buff(REDUCTION_BUFF), BuffInstance.FOREVER, 10, null, 0);

    for (int step = 0; step < 3; step++) {
      int before = knight.getHitPoints().getHitPoints();
      battle.getBattle().step();
      // The kill of the whole hit points less the reduction's share, truncated.
      int dealt = Math.max(before * (100 - REDUCTION) / 100, 1);
      assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(before - dealt);
      assertThat(battle.getWorld().getHolder().entities()).contains(knight);
    }
  }

  @Test
  @DisplayName(
      "the clearing's kill carries the hit points the unit had as the clearing reached it, so a"
          + " heal over time landing in the update before the drain is what the unit keeps")
  void theClearingKillLeavesTheHealSince() throws IOException {
    GameTables tables = written();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    battle.startLadderMatch(DECK, DECK, 0, 0);
    int timeUp = timeUpTick();
    CharacterEntity knight = battle.deploy(timeUp - 40, records.unit("Knight"), 11, 0, 3500, 12000);
    int[] raised = new int[1];
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffHealed(
                  int tick, WorldEntity target, BuffInstance buff, int amount, int before) {
                if (target == knight) {
                  raised[0] += target.getHitPoints().getHitPoints() - before;
                }
              }
            });
    stepTo(battle, timeUp + 1);
    knight.getBuffs().apply(records.buff(HEAL_BUFF), BuffInstance.FOREVER, 10, null, 0);
    raised[0] = 0;

    battle.getBattle().step();
    // The heal landed in the update the clearing ran, past the maximum the row allows; the kill
    // took the hit points from before it.
    assertThat(raised[0]).as("the heal raised the hit points").isPositive();
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(raised[0]);
    assertThat(battle.getWorld().getHolder().entities()).contains(knight);
  }

  /**
   * The configured tables copied with the two hand-written buffs: the reduction alone on {@link
   * #REDUCTION_BUFF}, the heal alone on {@link #HEAL_BUFF}, and a protection cap of 100.
   */
  private GameTables written() throws IOException {
    GameData.altered(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode reduction = ((ObjectNode) rows.get(REDUCTION_BUFF)).putObject("columns");
          reduction.put("DamageReduction", REDUCTION).put("Rarity", "Common");
          ObjectNode heal = ((ObjectNode) rows.get(HEAL_BUFF)).putObject("columns");
          heal.put("HealPerSecond", HEAL_PER_SECOND)
              .put("HitFrequency", 50)
              .put("AllowedOverHealPerc", 200)
              .put("Rarity", "Common");
        });
    GameData.alterLoaded(
        folder,
        "globals",
        rows -> GameData.columns(rows, "PROTECTION_CAP_PERCENTAGE").put("NumberValue", 100));
    return GameTables.load(folder);
  }

  /**
   * The tick the Ladder timeline's time is up on with the crowns equal: the end of its sections,
   * the regular one and overtime, each SectionLength seconds of 20 ticks.
   */
  private static int timeUpTick() {
    GameRow timeline =
        Shipped.row(
            "battle_timelines",
            Shipped.text(Shipped.row("game_modes", "Ladder"), "BattleTimeline"));
    return Shipped.numbers(timeline, "SectionLength").stream().mapToInt(s -> s * 20).sum();
  }

  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
