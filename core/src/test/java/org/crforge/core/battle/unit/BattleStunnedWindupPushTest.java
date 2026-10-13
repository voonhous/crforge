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
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The speed budget and the movement gates look the targeting component up through its active bit:
 * while the component is switched off they read none of its fields.
 *
 * <p>It shows on a unit that jumps as it dashes, stunned while it winds a dash up. The stun drops
 * its reference, and a jumping dasher keeps its wind-up through the drop, so that the dash goes on
 * at the same time once a reference is back; but with its targeting component off the wind-up no
 * longer stops it or shuts its push: a friendly unit walking into it pushes it while the stun
 * lasts. Read through a component that is off, the kept wind-up would hold it in place.
 *
 * <p>The scene: the bottom side's Mega Knight walks up to a standing enemy and winds a long dash
 * up; as the wind-up starts, a Mini Sparky of the top side stuns it with its hits and a friendly
 * Knight is placed right behind it, walking the same way. The towers are passive. The scene writes
 * every column its outcome is read from: the units' hit points, movement, mass, radius, range,
 * sight, attack pace and dash columns, the Mini Sparky's load and stun, and the stun's multipliers.
 */
class BattleStunnedWindupPushTest {

  /** The level the scene's units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** How long the stun of each Mini Sparky hit lasts, longer than the time between its hits. */
  private static final int STUN_TIME = 1000;

  /** The dash wind-up, long enough to outlast the scene's stun window. */
  private static final int DASH_COOLDOWN = 6000;

  /** Long enough for the Mega Knight to deploy, walk up and start winding up. */
  private static final int TICKS = 400;

  /** The steps of the stun window the scene reads. */
  private static final int WINDOW = 30;

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
              .put("LoadTime", 1200)
              .put("Damage", 10)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 5000)
              .put("DashCooldown", DASH_COOLDOWN)
              .put("DashConstantTime", 800)
              .put("DashLandingTime", 300)
              .put("DashDamage", 10)
              .put("DashRadius", 2200)
              .put("JumpHeight", 3000)
              .put("JumpSpeed", 250);
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 100_000)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 50)
              .put("Damage", 10);
          GameData.columns(rows, "Golem")
              .put("Hitpoints", 100_000)
              .put("Speed", 0)
              .put("Mass", 20)
              .put("CollisionRadius", 750)
              .put("Range", 500)
              .put("SightRange", 500)
              .put("DeployTime", 50)
              .put("Damage", 1);
          GameData.columns(rows, "MiniZapMachine")
              .put("Hitpoints", 100_000)
              .put("Speed", 0)
              .put("Range", 7000)
              .put("SightRange", 7000)
              .put("DeployTime", 50)
              .put("LoadTime", 100)
              .put("HitSpeed", 400)
              .put("Damage", 1)
              .put("BuffOnDamage", "ZapFreeze")
              .put("BuffOnDamageTime", STUN_TIME);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows ->
            GameData.columns(rows, "ZapFreeze")
                .put("HitSpeedMultiplier", -100)
                .put("SpeedMultiplier", -100)
                .put("SpawnSpeedMultiplier", -100));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a jumping dasher stunned in its wind-up keeps the wind-up but is pushed by a friend walking"
          + " into it: the gates read no field of a targeting component switched off")
  void aStunnedDasherInItsWindupIsPushed(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), LEVEL, false);
    // The enemy stands still across the river, the Mega Knight walks up to its dash range.
    match.deploy(0, match.getWorld().getRecords().unit("Golem"), LEVEL, 1, 3500, 23000, "golem");
    CharacterEntity megaKnight =
        match.deploy(
            0, match.getWorld().getRecords().unit("MegaKnight"), LEVEL, 0, 3500, 13000, "mk");
    while (megaKnight.getUnit().targeting().getDashWindupMs() <= 0) {
      assertThat(match.getBattle().getTick()).as("the wind-up starts").isLessThan(TICKS);
      match.getBattle().step();
    }
    int tick = match.getBattle().getTick();
    int x = megaKnight.getView().getX();
    int y = megaKnight.getView().getY();
    // The Mini Sparky stuns it from beside; the Knight walks into it from behind.
    match.deploy(
        tick,
        match.getWorld().getRecords().unit("MiniZapMachine"),
        LEVEL,
        1,
        x + 5000,
        y,
        "sparky");
    match.deploy(tick, match.getWorld().getRecords().unit("Knight"), LEVEL, 0, x, y - 1300, "kn");

    int stunnedHeld = 0;
    int movedWhileStunnedHeld = 0;
    int lastX = x;
    int lastY = y;
    for (int i = 0; i < WINDOW; i++) {
      match.getBattle().step();
      boolean stunned = megaKnight.getBuffs().carries("ZapFreeze");
      boolean windingUp = megaKnight.getUnit().targeting().getDashWindupMs() > 0;
      boolean targetingOff = !megaKnight.isActive(CharacterEntity.TARGETING_SLOT);
      int nowX = megaKnight.getView().getX();
      int nowY = megaKnight.getView().getY();
      if (stunned && windingUp && targetingOff) {
        stunnedHeld++;
        if (nowX != lastX || nowY != lastY) {
          movedWhileStunnedHeld++;
        }
      }
      lastX = nowX;
      lastY = nowY;
    }
    assertThat(stunnedHeld).as("steps stunned with the wind-up kept").isGreaterThan(1);
    assertThat(movedWhileStunnedHeld)
        .as("steps the stunned Mega Knight was pushed with its wind-up kept")
        .isGreaterThan(0);
  }
}
