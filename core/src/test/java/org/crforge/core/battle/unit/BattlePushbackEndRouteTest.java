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
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a unit walks toward once a pushback's flight has ended. The game drops the route the unit
 * held when the flight ends, so its next walk searches a fresh route from where the pushback left
 * it, rather than walking back toward the waypoint it held before the push.
 *
 * <p>The scene: the bottom side's Monk walks up the left lane, the top side's Giant comes down it,
 * and the Monk's third hit pushes the Giant sideways, the flight starting on tick 417. The flight's
 * budget runs out on tick 428 (the Giant stands) and its last visit on tick 429 steps it 25 units
 * back; tick 430 is its first walking step. The scene writes every column its outcome is read from
 * - both units' rows, the towers' places, the columns of theirs the walk reads and their shots - so
 * its ticks, cells and points are its own and not a version's.
 */
class BattlePushbackEndRouteTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The levels the cards are played at: a champion's and a rare card's first level. */
  private static final int MONK_LEVEL = 11;

  private static final int GIANT_LEVEL = 3;

  /** The tick of the flight's last visit: the 25 step back. */
  private static final int FLIGHT_END = 429;

  /** The width of the routing grid, in cells, which turns a cell into a route node. */
  private static final int WIDTH = 36;

  /** The routing cell the Giant headed for before the push: column 8, row 36. */
  private static final int HELD_WAYPOINT = 36 * WIDTH + 8;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Monk")
              .put("Hitpoints", 865)
              .put("Damage", 55)
              .put("VariableDamage2", 55)
              .put("VariableDamage3", 165)
              .put("MeleePushback3", 1800)
              .put("HitSpeed", 800)
              .put("LoadTime", 600)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450)
              .putArray("AttackSequence")
              .add(0)
              .add(1)
              .add(2);
          GameData.columns(rows, "Giant")
              .put("Hitpoints", 1550)
              .put("Damage", 99)
              .put("HitSpeed", 1500)
              .put("LoadTime", 1000)
              .put("Speed", 45)
              .put("StopMovementAfterMS", 640)
              .put("WaitMS", 100)
              .put("Mass", 18)
              .put("CollisionRadius", 750)
              .put("Range", 1200)
              .put("SightRange", 7500)
              .put("SightClip", 2000)
              .put("SightClipSide", 2000)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  /** The towers at the first level, fighting; the Monk and the Giant played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(220, match.getWorld().getRecords().card("Monk"), MONK_LEVEL, 0, 3500, 14000, "M");
    match.play(300, match.getWorld().getRecords().card("Giant"), GIANT_LEVEL, 1, 3500, 21500, "G");
    return match;
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The Giant, the second play's unit. */
  private static CharacterEntity giant(Standard1v1Battle match) {
    return match.getPlays().get(1).units().get(0);
  }

  @Test
  @DisplayName(
      "a pushback's end drops the Giant's route, and its first step"
          + " follows a fresh route from where the push left it")
  void theEndOfThePushbackDropsTheRoute(@TempDir Path folder) throws IOException {
    assertTheRouteIsDropped(scene(written(folder)));
  }

  /** The Giant's route dropped as its flight ends, and its next step on a fresh route. */
  private static void assertTheRouteIsDropped(Standard1v1Battle match) {
    stepTo(match, FLIGHT_END - 1);
    MovementState movement = giant(match).getUnit().movement();
    assertThat(movement.getPushbackInFlight()).as("in flight, standing").isEqualTo(1);
    assertThat(movement.getRoute().last()).as("the route held").isEqualTo(HELD_WAYPOINT);

    stepTo(match, FLIGHT_END);
    assertThat(movement.getPushbackInFlight()).as("the flight ended").isZero();
    assertThat(giant(match).getView().getX()).as("stepped back").isEqualTo(5882);
    assertThat(giant(match).getView().getY()).isEqualTo(19491);
    assertThat(movement.getRoute().isEmpty()).as("the route dropped").isTrue();
    assertThat(movement.getRouteLeadsAway()).isZero();

    stepTo(match, FLIGHT_END + 1);
    assertThat(movement.getRoute().last())
        .as("a fresh route's next cell: column 9, row 36")
        .isEqualTo(36 * WIDTH + 9);
    assertThat(giant(match).getView().getX()).isEqualTo(5849);
    assertThat(giant(match).getView().getY()).isEqualTo(19452);
  }
}
