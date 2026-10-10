/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every change of a unit's state ends with its combat gate, wherever the change is asked for, not
 * only at the tail of the unit's state visit.
 *
 * <p>It shows on a unit that dashes, held standing under NO_MOVE with a reference out of its range,
 * when a stun lands on it. Each tick of the hold its targeting visit gives the reference up for a
 * moment and resumes it, asking for the moving state, and its movement visit puts it back to
 * standing under NO_MOVE. On the first tick the stun is on it, the gate at the end of that resume's
 * change finds the stun, drops the reference and resumes the unit once more, all before the
 * movement visit, which puts it back to standing as on every other tick: the unit never shows the
 * moving state. Had the gate waited for the state visit, after the movement visit, its drop and
 * resume would leave the unit moving at the end of that tick.
 *
 * <p>The scene: the bottom side's evolved Mega Knight uppercuts a Knight away and is held for the
 * uppercut's follow-up delay; a Mini Sparky of the top side, placed as the hold starts, stuns it
 * with its hits. The towers are passive. The scene writes every column its outcome is read from:
 * the units' hit points, movement, range, sight, attack pace and dash columns, the Mini Sparky's
 * deploy, load and stun, and the stun's multipliers.
 */
class BattleHeldDasherStunTest {

  /** The level the scene's units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** How long the stun of each Mini Sparky hit lasts, in milliseconds. */
  private static final int STUN_TIME = 300;

  /** Long enough for the Mega Knight to deploy, reach the Knight and uppercut it. */
  private static final int TICKS = 400;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.copyConfigured(folder);
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "MegaKnight_EV1")
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
              .put("DashCooldown", 900)
              .put("DashConstantTime", 800)
              .put("DashLandingTime", 300)
              .put("DashDamage", 10)
              .put("DashRadius", 2200)
              .put("JumpHeight", 3000)
              .put("JumpSpeed", 250);
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 100_000)
              .put("Speed", 60)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("Damage", 10);
          GameData.columns(rows, "MiniZapMachine")
              .put("Hitpoints", 100_000)
              .put("Speed", 60)
              .put("Range", 6000)
              .put("SightRange", 6000)
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
      "a dasher held standing under NO_MOVE never shows the moving state when a stun drops its"
          + " reference: the gate at the end of each state change drops it before the movement"
          + " visit")
  void aHeldDasherStaysStandingWhenStunned(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), LEVEL, false);
    List<Integer> started = new ArrayList<>();
    List<Integer> ended = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void uppercutStarted(
                  int tick,
                  CharacterEntity unit,
                  String action,
                  int phase,
                  WorldEntity instigator,
                  WorldEntity target,
                  boolean finished) {
                started.add(tick);
              }

              @Override
              public void uppercutStepped(
                  int tick,
                  CharacterEntity unit,
                  int delay,
                  boolean finished,
                  String outcome,
                  int[] pushPoint) {
                if (finished) {
                  ended.add(tick);
                }
              }
            });
    match.deploy(0, match.getWorld().getRecords().unit("Knight"), LEVEL, 1, 3500, 20500, "knight");
    CharacterEntity megaKnight =
        match.deploy(
            0, match.getWorld().getRecords().unit("MegaKnight_EV1"), LEVEL, 0, 3500, 18000, "mk");
    while (started.isEmpty()) {
      assertThat(match.getBattle().getTick()).as("the uppercut starts").isLessThan(TICKS);
      match.getBattle().step();
    }
    // The Mini Sparky stands beside the held Mega Knight, out of its range, in its own.
    match.deploy(
        match.getBattle().getTick(),
        match.getWorld().getRecords().unit("MiniZapMachine"),
        LEVEL,
        1,
        megaKnight.getView().getX() + 4500,
        megaKnight.getView().getY(),
        "sparky");

    long noMove = megaKnight.getView().getFlagBits().noMove();
    int heldTicks = 0;
    int stunsWhileHeld = 0;
    boolean stunnedBefore = false;
    while (ended.isEmpty()) {
      assertThat(match.getBattle().getTick()).as("the hold ends").isLessThan(TICKS * 2);
      match.getBattle().step();
      int tick = match.getBattle().getTick();
      // The tag word the step ran with: folded from what was raised in the step before.
      boolean held = (megaKnight.getView().getFlags() & noMove) != 0;
      boolean stunned = megaKnight.getBuffs().carries("ZapFreeze");
      if (held) {
        heldTicks++;
        if (stunned && !stunnedBefore) {
          stunsWhileHeld++;
        }
        assertThat(megaKnight.getView().getState())
            .as("the held Mega Knight stands, tick %d", tick)
            .isEqualTo(GridEntityState.STANDING);
      }
      stunnedBefore = stunned;
    }
    assertThat(heldTicks).as("ticks held").isGreaterThan(1);
    assertThat(stunsWhileHeld).as("stuns landing while held").isGreaterThan(0);
  }
}
