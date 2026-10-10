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
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The minimum range in the choice of a target: a unit with a MinimumRange passes over a candidate
 * whose centre stands closer than the candidate's radius plus the unit's minimum range, the unit's
 * own collision radius counted once in it. A Mortar picks the nearest enemy beyond that line, so
 * the first shot into a group goes to the nearest one standing just outside its minimum range. The
 * scene writes every column the choice reads: the Mortar's and the three Cannons' rows.
 */
class BattleMinimumRangeSelectionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Mortar's own collision radius. */
  private static final int MORTAR_RADIUS = 600;

  /** The Mortar's minimum range, to the candidate's edge. */
  private static final int MINIMUM_RANGE = 3500;

  /** Each Cannon's collision radius. */
  private static final int CANNON_RADIUS = 600;

  /** The closest a candidate's centre may stand: its radius, the Mortar's and the minimum. */
  private static final int LINE = CANNON_RADIUS + MORTAR_RADIUS + MINIMUM_RANGE;

  /** Where the Mortar stands, clear of every tower's reach. */
  private static final int MORTAR_X = 9000;

  private static final int MORTAR_Y = 9000;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "buildings",
        rows -> {
          GameData.columns(rows, "Mortar")
              .put("CollisionRadius", MORTAR_RADIUS)
              .put("MinimumRange", MINIMUM_RANGE)
              .put("Range", 11500)
              .put("SightRange", 11500)
              .put("DeployTime", 1000)
              .put("Hitpoints", 535)
              .put("HitSpeed", 4700)
              .put("LoadTime", 3700)
              .put("LifeTime", 30000);
          GameData.columns(rows, "Cannon")
              .put("CollisionRadius", CANNON_RADIUS)
              .put("Range", 500)
              .put("SightRange", 500)
              .put("DeployTime", 1000)
              .put("Hitpoints", 1000)
              .put("HitSpeed", 1000)
              .put("LifeTime", 30000);
        });
  }

  @Test
  @DisplayName(
      "a Mortar passes over a Cannon inside its minimum range and takes the nearest one standing"
          + " exactly on the line, the radius of the Mortar counted once")
  void theMortarTakesTheNearestCandidateBeyondItsMinimumRange(@TempDir Path folder)
      throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), LEVEL, false);
    UnitData cannon = match.getWorld().getRecords().unit("Cannon");
    // Inside the line: straight up, 100 short of it.
    match.deploy(0, cannon, LEVEL, 1, MORTAR_X, MORTAR_Y + LINE - 100, "inside");
    // On the line: 4700 away along a 3-4-5 diagonal, which the test accepts (at or beyond it).
    match.deploy(0, cannon, LEVEL, 1, MORTAR_X - 2820, MORTAR_Y + 3760, "on_the_line");
    // Well beyond the line, and farther than the one on it.
    match.deploy(0, cannon, LEVEL, 1, MORTAR_X + 3600, MORTAR_Y + 4800, "beyond");
    CharacterEntity mortar =
        match.deploy(
            40,
            match.getWorld().getRecords().unit("Mortar"),
            LEVEL,
            0,
            MORTAR_X,
            MORTAR_Y,
            "mortar");
    assertThat(LINE).isEqualTo(4700);

    TargetView reference = null;
    for (int tick = 0; tick < 200 && reference == null; tick++) {
      match.getBattle().step();
      if (tick > 40) {
        reference = mortar.getTargeting().getReference();
      }
    }

    assertThat(reference).isNotNull();
    assertThat(reference.getEntity().getName()).isEqualTo("on_the_line");
  }
}
