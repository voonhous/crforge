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
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A heal over time on a Kamikaze unit as its hit ends. The unit's kill of itself is queued for the
 * damage drain with the hit points it has as its hit ends, and its buffs are visited after its hit
 * in the same step. The game gives no heal over time to a unit whose Kamikaze hit has ended, so the
 * kill takes all it has and the unit dies on the step of its hit, as a Battle Ram under a Heal
 * Spirit's heal does. A heal landing there would leave the unit standing on its share until the
 * next step.
 *
 * <p>The scene: side 0's Fire Spirit and side 1's Valkyrie, played on the same tick in the left
 * lane, meet at the river, and the Fire Spirit's hit ends with its own kill. A few ticks before its
 * hit the spirit is given a buff written for the test whose only columns are a heal over time each
 * step that may heal past the maximum. The scene writes every column its outcome is read from, so
 * its ticks are its own and not a version's.
 */
class BattleKamikazeHealDropTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The tick of the Fire Spirit's hit, which ends with its kill. */
  private static final int KILL = 128;

  /** The tick the heal is put on the spirit, after its deploy and before its hit. */
  private static final int HEALED = KILL - 5;

  /** The heal's time, far past the scene's end. */
  private static final int HEAL_MS = 10_000;

  /** The last tick the scene is played to. */
  private static final int LAST_TICK = KILL + 20;

  /** The heal written for the test. */
  private static final String HEAL = "Test_Kamikaze_Heal";

  /** The configured tables with the scene's columns and the heal written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "FireSpirits")
              .put("Hitpoints", 84)
              .put("HitSpeed", 300)
              .put("LoadTime", 100)
              .put("Speed", 120)
              .put("Mass", 1)
              .put("CollisionRadius", 400)
              .put("Range", 2500)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("DeployDelay", 400)
              .put("SpawnRadius", 650)
              .put("Kamikaze", true);
          GameData.columns(rows, "Valkyrie")
              .put("Hitpoints", 745)
              .put("Damage", 104)
              .put("AreaDamageRadius", 2000)
              .put("AttackFinishTime", 100)
              .put("HitSpeed", 1500)
              .put("LoadTime", 1400)
              .put("Speed", 60)
              .put("Mass", 5)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, "FireSpiritsProjectile")
                .put("Damage", 84)
                .put("Radius", 2300)
                .put("Speed", 400)
                .put("Gravity", 400));
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows ->
            GameData.columns(rows, "FireSpirits")
                .put("SummonNumber", 1)
                .put("SummonDeployDelay", 100));
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode row = rows.putObject(HEAL);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicCharacterBuffData");
          ObjectNode columns = row.putObject("columns");
          columns.put("HealPerSecond", 600);
          columns.put("HitFrequency", 50);
          columns.put("AllowedOverHealPerc", 200);
          columns.put("Rarity", "Common");
        });
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a Kamikaze spirit under a heal over time takes no heal once its hit has ended, so its kill"
          + " takes all it has and it dies on the step of its hit")
  void theKamikazeHitEndDropsTheHeal(@TempDir Path folder) throws IOException {
    GameTables tables = written(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    List<Integer> heals = new ArrayList<>();
    List<Integer> kills = new ArrayList<>();
    match.play(100, match.getWorld().getRecords().card("FireSpirits"), 1, 0, 3500, 14500, "F");
    match.play(100, match.getWorld().getRecords().card("Valkyrie"), 1, 1, 3500, 18500, "V");
    while (match.getBattle().getTick() < HEALED) {
      match.getBattle().step();
    }
    CharacterEntity spirit = match.getPlays().get(0).units().get(0);
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffHealed(
                  int tick, WorldEntity target, BuffInstance buff, int amount, int before) {
                if (target == spirit) {
                  heals.add(tick);
                }
              }

              @Override
              public void kamikazeKilled(
                  int tick, WorldEntity unit, int damage, DamageResult result) {
                if (unit == spirit) {
                  kills.add(tick);
                }
              }
            });
    spirit.getBuffs().apply(match.getWorld().getRecords().buff(HEAL), HEAL_MS, 0, null, 0);
    assertThat(spirit.getBuffs().carries(HEAL)).isTrue();

    while (match.getBattle().getTick() < KILL) {
      match.getBattle().step();
    }
    // The heal landed on every step before the hit and none on the step of the hit.
    assertThat(kills).as("its hit ended with its kill").containsExactly(KILL - 1);
    assertThat(heals).as("healed before its hit").isNotEmpty().allMatch(tick -> tick < KILL - 1);
    assertThat(HitPoints.alive(spirit.getHitPoints())).as("its kill took all it had").isFalse();
    while (match.getBattle().getTick() < LAST_TICK) {
      match.getBattle().step();
    }
    assertThat(match.getWorld().getHolder().entities()).doesNotContain(spirit);
  }
}
