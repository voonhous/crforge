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
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dark Magic's laser ball on a unit that still waits its turn to deploy: its filter asks the hidden
 * test, which answers yes for a unit in that state, so the fire passes the waiting unit by and
 * picks the others.
 *
 * <p>The scene: the top side's Goblins are played on tick 32; the four of them start deploying one
 * after another, the last on tick 44. The bottom side's Dark Magic area effect is placed on them so
 * that its laser ball first fires on tick 40, while the last Goblin still waits.
 *
 * <p>The scene writes what it counts on: the Goblins card summons four Goblin_Stab 200 ms apart in
 * a circle of 700, each waiting 400 ms and deploying 1000 ms; the laser ball starts 500 ms after
 * its area effect, fires 1000 ms after its start and detects in a circle of 2500.
 */
class BattleLaserBallWaitingTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The tick the laser ball first fires on. */
  private static final int FIRE_TICK = 40;

  /**
   * Writes Dark Magic's laser ball into the actions as the scenes count on it: it starts 500 ms
   * after the area effect, fires 1000 ms after its start and every 1000 ms after, detects in a
   * circle of 2500, and picks its first list for one unit and its second for up to four.
   */
  private static void laserBall(ObjectNode rows) {
    ((ObjectNode) rows.get("DarkMagicAOE_OnStartingAction").get("fields"))
        .putArray("SubActionsDelay")
        .add(0)
        .add(500);
    ObjectNode laser =
        (ObjectNode) rows.get("DarkMagicAOE_OnStartingAction_SubActions1").get("fields");
    laser.put("FirstHitDelay", 1000);
    laser.put("HitFrequency", 1000);
    laser.put("DetectionRadius", 2500);
    laser.putArray("MaxUnitPerActionList").add(1).add(4);
  }

  /** The configured tables with the columns the scene counts on written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(folder, "actions", BattleLaserBallWaitingTest::laserBall);
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows ->
            GameData.columns(rows, "Goblins")
                .put("SummonCharacter", "Goblin_Stab")
                .put("SummonNumber", 4)
                .put("SummonDeployDelay", 200)
                .put("SummonRadius", 700));
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Goblin_Stab").put("DeployDelay", 400).put("DeployTime", 1000));
    return GameTables.load(folder);
  }

  /** What one fire found: its tick, its count and the units it picked. */
  private record Fire(int tick, int count, List<WorldEntity> targets) {}

  @Test
  @DisplayName("a laser ball's fire passes a unit waiting to deploy by")
  void laserBallPassesAWaitingUnitBy(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), 1, false);
    List<Fire> fires = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void laserFired(
                  int tick,
                  AreaEffectEntity areaEffect,
                  int count,
                  int index,
                  List<WorldEntity> targets,
                  String action,
                  int timerBefore,
                  int timerAfter) {
                fires.add(new Fire(tick, count, List.copyOf(targets)));
              }
            });
    match.play(32, match.getWorld().getRecords().card("Goblins"), 1, 1, 13500, 22500, "Goblins");
    // The laser ball starts ten ticks after the area effect, and fires twenty ticks later.
    match.placeAreaEffect(FIRE_TICK - 30, "DarkMagicAOE", LEVEL, 0, 13500, 22500, "dark");
    while (match.getBattle().getTick() <= FIRE_TICK) {
      match.getBattle().step();
    }
    List<CharacterEntity> goblins =
        match.getPlays().stream()
            .filter(play -> play.units().size() == 4)
            .findFirst()
            .orElseThrow()
            .units();
    assertThat(goblins.get(3).getView().getState())
        .as("the last Goblin still waits")
        .isEqualTo(GridEntityState.WAITING_TO_DEPLOY);
    assertThat(fires).as("the laser ball fired once").hasSize(1);
    Fire fire = fires.get(0);
    assertThat(fire.tick()).isEqualTo(FIRE_TICK);
    assertThat(fire.targets())
        .as("the fire picks the three Goblins out and passes the waiting one by")
        .contains(goblins.get(0), goblins.get(1), goblins.get(2))
        .doesNotContain(goblins.get(3));
  }
}
