/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The end of a dash's landing hold resets the attack and reloads the wind-up when the targeting
 * component is on, and only then asks for the moving state, whose change ends with the combat gate.
 * So the component is read as the gate of the visit before left it: a freeze that lands on the tick
 * the hold ends, before the dasher's state visit, still sees the wind-up reloaded, and the gate of
 * that same change then switches the component off for the freeze. The load runs down only once the
 * freeze is over, so the first hit after it comes a whole load later.
 *
 * <p>The scene: the bottom side's Mega Knight jumps at a red Knight ahead of it; the freeze is put
 * on it between the steps, on the last tick of its landing hold. The towers are passive. The scene
 * writes every column its outcome is read from: the units' hit points, movement, range, sight,
 * attack pace and dash columns, and the freeze's multipliers.
 */
class BattleLandingFreezeReloadTest {

  /** The level the scene's units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** The Mega Knight's wind-up before a hit, in milliseconds. */
  private static final int LOAD_TIME = 1200;

  /** The Mega Knight's landing hold, in milliseconds. */
  private static final int LANDING_TIME = 300;

  /** How long the freeze lasts, in milliseconds. */
  private static final int FREEZE_TIME = 1100;

  /** The freeze buff the scene puts on the Mega Knight. */
  private static final String FREEZE = "Freeze";

  /** Long enough for the Mega Knight to deploy and jump at the Knight. */
  private static final int TICKS = 200;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.copyConfigured(folder);
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "MegaKnight")
              .put("Hitpoints", 100_000)
              .put("Speed", 60)
              .put("Mass", 18)
              .put("CollisionRadius", 750)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("HitSpeed", 1700)
              .put("LoadTime", LOAD_TIME)
              .put("Damage", 10)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 5000)
              .put("DashCooldown", 900)
              .put("DashConstantTime", 800)
              .put("DashLandingTime", LANDING_TIME)
              .put("DashDamage", 10)
              .put("DashRadius", 2200)
              .put("JumpHeight", 3000)
              .put("JumpSpeed", 250)
              .put("SpawnPushback", 0)
              .put("SpawnPushbackRadius", 0);
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 100_000)
              .put("Speed", 60)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("Damage", 10);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows ->
            GameData.columns(rows, FREEZE)
                .put("HitSpeedMultiplier", -100)
                .put("SpeedMultiplier", -100)
                .put("SpawnSpeedMultiplier", -100));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a freeze landing on the tick a Mega Knight's landing hold ends still sees its wind-up"
          + " reloaded: the reload reads the targeting component before the moving state's gate"
          + " switches it off")
  void aFreezeOnTheLandingTickStillReloadsTheWindUp(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), LEVEL, false);
    BattleRecords records = match.getWorld().getRecords();
    match.deploy(0, records.unit("Knight"), LEVEL, 1, 3500, 18000, "knight");
    CharacterEntity megaKnight =
        match.deploy(0, records.unit("MegaKnight"), LEVEL, 0, 3500, 10000, "mk");

    // Step until the next state visit ends the landing hold.
    boolean dashed = false;
    while (!dashed
        || megaKnight.getView().getBlockCountdownMs() < 1
        || megaKnight.getView().getBlockCountdownMs() + 50 < LANDING_TIME) {
      assertThat(match.getBattle().getTick()).as("the Mega Knight lands").isLessThan(TICKS);
      match.getBattle().step();
      dashed |= megaKnight.getView().getState() == GridEntityState.DASHING;
    }
    TargetingState targeting = megaKnight.getTargeting();
    assertThat(targeting.getLoadTimerMs()).as("the wind-up is loaded before the jump").isZero();
    assertThat(megaKnight.isActive(CharacterEntity.TARGETING_SLOT)).isTrue();

    // The freeze lands between the steps, after the gate of the hold's last visit but one.
    megaKnight
        .getBuffs()
        .apply(records.buff(FREEZE), FREEZE_TIME, megaKnight.getPackedLevel(), null, 1);
    match.getBattle().step();

    assertThat(megaKnight.getView().getState())
        .as("the hold ended")
        .isEqualTo(GridEntityState.MOVING);
    assertThat(megaKnight.isActive(CharacterEntity.TARGETING_SLOT))
        .as("the moving state's gate switched the frozen Mega Knight's component off")
        .isFalse();
    assertThat(targeting.getLoadTimerMs())
        .as("the wind-up reloaded as the hold ended, the component still on")
        .isEqualTo(LOAD_TIME);
    assertThat(targeting.getAttackTimerMs()).as("the attack reset").isZero();

    // While the freeze lasts, no targeting visit runs the load down.
    while (megaKnight.getBuffs().carries(FREEZE)) {
      assertThat(match.getBattle().getTick()).as("the freeze ends").isLessThan(TICKS * 2);
      match.getBattle().step();
      if (!megaKnight.isActive(CharacterEntity.TARGETING_SLOT)) {
        assertThat(targeting.getLoadTimerMs()).isEqualTo(LOAD_TIME);
      }
    }
  }
}
