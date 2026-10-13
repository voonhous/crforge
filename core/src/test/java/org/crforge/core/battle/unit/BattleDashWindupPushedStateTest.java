/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pushback that lands during a dash wind-up resumes the dasher: a unit still in the attacking
 * state of the attack it just finished walks (the moving state) while it is pushed, and its wind-up
 * runs on, so the dash still starts when the wind-up runs out.
 *
 * <p>The scene: the bottom side's Bandit, which dashes at a target between its dash's least and
 * greatest range, kills a weak enemy troop in front of it in one hit; the enemy Inferno Tower
 * further up the lane then stands in its dash range, so the Bandit winds up its dash at the tower
 * still in the attacking state of its last hit. As the wind-up is about to run out an area pushes
 * it back. The scene writes every column its outcome is read from: the rows' ranges, radii, pace,
 * damage and dash, and hit points no hit of the scene takes but the troop's.
 */
class BattleDashWindupPushedStateTest {

  /** The level every unit is placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Long enough for the Bandit to kill the troop, wind up, be pushed and dash. */
  private static final int TICKS = 400;

  /** How far the area pushes the Bandit back. */
  private static final int PUSH = 600;

  /** The wind-up left, in milliseconds, at which the area pushes the Bandit. */
  private static final int WINDUP_LEFT = 200;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Assassin")
              .put("Hitpoints", 1_000_000)
              .put("Damage", 76)
              .put("HitSpeed", 1000)
              .put("LoadTime", 600)
              .put("Speed", 90)
              .put("Mass", 3)
              .put("CollisionRadius", 600)
              .put("Range", 750)
              .put("SightRange", 6000)
              .put("DeployTime", 1000)
              .put("DashDamage", 152)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 6000)
              .put("DashCooldown", 800)
              .put("DashImmuneToDamageTime", 150)
              .put("JumpSpeed", 500);
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 50)
              .put("Damage", 1)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000);
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> {
          GameData.writeTowerColumns(rows);
          GameData.columns(rows, "InfernoTower")
              .put("Hitpoints", 1_000_000)
              .put("Damage", 20)
              .put("VariableDamage2", 0)
              .put("VariableDamage3", 0)
              .put("VariableDamageTime1", 0)
              .put("VariableDamageTime2", 0)
              .put("HitSpeed", 400)
              .put("LoadTime", 1200)
              .put("CollisionRadius", 1000)
              .put("Range", 6000)
              .put("SightRange", 6000)
              .put("DeployTime", 1000);
        });
    GameData.alterLoaded(folder, "projectiles", GameData::writeTowerShots);
    GameData.alterLoaded(folder, "spawn_groups", GameData::placeTowers);
    return GameTables.load(folder);
  }

  /**
   * What the scene saw: the Bandit's state as the push came and on the step after it, and whether
   * the dash started after the push.
   */
  private record Seen(int stateAtPush, int stateAfterPush, boolean dashedAfterPush) {}

  /** Plays the scene, the towers passive. */
  private static Seen play(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(
        0, match.getWorld().getRecords().unit("InfernoTower"), LEVEL, 1, 3500, 14500, "Tower");
    match.deploy(0, match.getWorld().getRecords().unit("Knight"), LEVEL, 1, 3500, 10400, "Troop");
    CharacterEntity bandit =
        match.deploy(
            0, match.getWorld().getRecords().unit("Assassin"), LEVEL, 0, 3500, 9000, "Bandit");
    int stateAtPush = -1;
    int stateAfterPush = -1;
    boolean dashed = false;
    for (int step = 0; step < TICKS && !dashed; step++) {
      match.getBattle().step();
      int state = bandit.getView().getState();
      int windup = bandit.getTargeting().getDashWindupMs();
      if (stateAtPush < 0 && windup >= 1 && windup <= WINDUP_LEFT) {
        stateAtPush = state;
        // An area in front of the Bandit, between it and the tower, pushes it back.
        bandit.pushedByArea(bandit.getView().getX(), bandit.getView().getY() + 100, PUSH);
      } else if (stateAtPush >= 0 && stateAfterPush < 0) {
        stateAfterPush = state;
      } else if (stateAfterPush >= 0) {
        dashed = state == GridEntityState.DASHING;
      }
    }
    return new Seen(stateAtPush, stateAfterPush, dashed);
  }

  @Test
  @DisplayName(
      "a pushback during a dash wind-up turns a unit still showing its last attack to walking,"
          + " and the dash still starts")
  void aPushDuringTheWindupResumesTheUnit(@TempDir Path folder) throws IOException {
    Seen scene = play(written(folder));
    assertThat(scene.stateAtPush())
        .as("the Bandit wound up its dash still in the state of its last attack")
        .isEqualTo(GridEntityState.ATTACKING);
    assertThat(scene.stateAfterPush())
        .as("pushed during the wind-up, the Bandit walks")
        .isEqualTo(GridEntityState.MOVING);
    assertThat(scene.dashedAfterPush()).as("the wind-up ran on and the dash started").isTrue();
  }
}
