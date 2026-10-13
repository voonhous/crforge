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
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dash that starts while a pushback is still in flight. The pushback's end drops the route the
 * unit holds, here the dash's single node; with no route, a unit whose reference stands beyond its
 * attack range heads for the point its attack range away from the reference, so the dash goes on
 * toward its target instead of landing where the pushback left the unit.
 *
 * <p>The scene: the top side's Inferno Tower stands in the left lane, and the bottom side's Bandit,
 * which dashes at a target between its dash's least and greatest range, walks up the lane at it. As
 * the Bandit's dash wind-up is about to run out an area pushes it back, away from the tower, so the
 * dash starts with the pushback in flight. The scene writes every column its outcome is read from:
 * both rows' ranges, radii, pace and dash, and hit points no hit of the scene takes.
 */
class BattleDashPushedRouteTest {

  /** The level both units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Long enough for the Bandit to walk into its dash range, wind up, be pushed and dash. */
  private static final int TICKS = 400;

  /** How far the area pushes the Bandit back. */
  private static final int PUSH = 600;

  /** The wind-up left, in milliseconds, at which the area pushes the Bandit. */
  private static final int WINDUP_LEFT = 100;

  /** The Bandit's least dash range, which a dash that reached the tower ends well inside. */
  private static final int DASH_MIN_RANGE = 3500;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows ->
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
                .put("DashMinRange", DASH_MIN_RANGE)
                .put("DashMaxRange", 6000)
                .put("DashCooldown", 800)
                .put("DashImmuneToDamageTime", 150)
                .put("JumpSpeed", 500));
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
   * What the scene saw: whether the dash started with the pushback in flight, where the push left
   * the Bandit, and where its dash ended, as distances from the tower.
   */
  private record Seen(boolean dashedWhilePushed, int pushedTo, int landedAt) {}

  /** Plays the scene, the towers passive. */
  private static Seen play(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity tower =
        match.deploy(
            0, match.getWorld().getRecords().unit("InfernoTower"), LEVEL, 1, 3500, 18000, "Tower");
    CharacterEntity bandit =
        match.deploy(
            0, match.getWorld().getRecords().unit("Assassin"), LEVEL, 0, 3500, 9000, "Bandit");
    boolean pushed = false;
    boolean dashedWhilePushed = false;
    int pushedTo = -1;
    int landedAt = -1;
    for (int step = 0; step < TICKS && landedAt < 0; step++) {
      match.getBattle().step();
      int state = bandit.getView().getState();
      int windup = bandit.getTargeting().getDashWindupMs();
      boolean inFlight = bandit.getUnit().movement().getPushbackInFlight() != 0;
      if (!pushed && windup >= 1 && windup <= WINDUP_LEFT) {
        // An area in front of the Bandit, between it and the tower, pushes it back.
        pushed = bandit.pushedByArea(bandit.getView().getX(), bandit.getView().getY() + 100, PUSH);
      } else if (pushed && state == GridEntityState.DASHING) {
        dashedWhilePushed |= inFlight;
        if (inFlight || pushedTo < 0) {
          pushedTo = distance(bandit, tower);
        }
      } else if (pushed && dashedWhilePushed) {
        landedAt = distance(bandit, tower);
      }
    }
    return new Seen(dashedWhilePushed, pushedTo, landedAt);
  }

  private static int distance(CharacterEntity a, CharacterEntity b) {
    return FixedMath.guardedDistance(
        a.getView().getX() - b.getView().getX(), a.getView().getY() - b.getView().getY());
  }

  @Test
  @DisplayName(
      "a dash that starts while a pushback flies goes on toward its target after the pushback's"
          + " end drops its route")
  void aPushedDashGoesOnTowardItsTarget(@TempDir Path folder) throws IOException {
    Seen scene = play(written(folder));
    assertThat(scene.dashedWhilePushed())
        .as("the dash started with the pushback in flight")
        .isTrue();
    assertThat(scene.pushedTo())
        .as("pushed out beyond the least dash range")
        .isGreaterThan(DASH_MIN_RANGE);
    assertThat(scene.landedAt())
        .as("the dash went on and ended at the tower, not where the push left the Bandit")
        .isPositive()
        .isLessThan(DASH_MIN_RANGE);
  }
}
