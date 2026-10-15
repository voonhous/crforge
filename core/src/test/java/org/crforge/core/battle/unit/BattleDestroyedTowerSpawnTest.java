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
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the side list of princess towers for a unit made on the tick a princess tower is destroyed:
 * the side list is one list per side, which the destroyed tower leaves at the cleanup of the tick
 * it died, so a unit handed over during that tick, and admitted only at that cleanup, no longer
 * finds it there from the next tick on, as every unit already in the battle does.
 *
 * <p>The scene: the towers passive and at the first level; side 0's Knight, whose one hit brings
 * the top side's left princess tower down, and side 0's Tombstone in the left lane of its own half,
 * which makes one Skeleton every visit. The scene writes every column its outcome is read from, so
 * its ticks are its own and not a version's.
 */
class BattleDestroyedTowerSpawnTest {

  /** The top side's left princess tower, the one the Knight destroys. */
  private static final String DESTROYED = "PrincessTower_1_1";

  /** The tick the scene stops at, well after the tower falls. */
  private static final int LAST_TICK = 200;

  /** One Skeleton made in the scene: the tick and the unit. */
  private record Made(int tick, CharacterEntity unit) {}

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 3000)
              .put("Damage", 5000)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("Speed", 60)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000);
          GameData.columns(rows, "Skeleton")
              .put("Speed", 60)
              .put("CollisionRadius", 300)
              .put("SightRange", 1000)
              .put("DeployTime", 1000);
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows ->
            GameData.columns(rows, "Tombstone")
                .put("SpawnCharacter", "Skeleton")
                .put("SpawnNumber", 1)
                .put("SpawnInterval", 0)
                .put("SpawnPauseTime", 50)
                .put("SpawnStartTime", 0)
                .put("DeployTime", 1000)
                .put("LifeTime", 30000));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a unit made on the tick a princess tower is destroyed no longer finds that tower in its side"
          + " list, and walks at the king")
  void aUnitMadeOnTheTowersLastTickForgetsIt(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), 1, false);
    List<Made> made = new ArrayList<>();
    int[] at = {0};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                if (child.getData().name().equals("Skeleton")) {
                  made.add(new Made(at[0], child));
                }
              }
            });
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), DESTROYED);
    match.deploy(0, match.getWorld().getRecords().unit("Knight"), 1, 0, 3500, 21000);
    match.deploy(0, match.getWorld().getRecords().unit("Tombstone"), 1, 0, 3500, 4000);

    // The last tick the tower stands through: it dies in that tick's hit and leaves at its
    // closing cleanup.
    int lastTick = -1;
    for (int tick = 0; tick < LAST_TICK; tick++) {
      at[0] = tick;
      match.getBattle().step();
      if (lastTick < 0 && !tower.getTargetView().alive()) {
        lastTick = tick;
      }
    }
    assertThat(lastTick).as("the Knight brings the tower down").isPositive();
    int fell = lastTick;
    List<CharacterEntity> sameTick =
        made.stream().filter(m -> m.tick() == fell).map(Made::unit).toList();
    assertThat(sameTick).as("the Tombstone makes a Skeleton on the tower's last tick").hasSize(1);
    CharacterEntity skeleton = sameTick.get(0);

    assertThat(skeleton.getUnit().selection().getRegisteredTowers())
        .as("the side list the Skeleton reads holds no destroyed tower")
        .extracting(TargetView::name)
        .doesNotContain(DESTROYED);
    // With the left princess tower gone, the one left stands in the other lane and is passed over:
    // the Skeleton walks at the king.
    assertThat(skeleton.getUnit().targeting().getReference())
        .as("the Skeleton's reference once it walks")
        .isNotNull()
        .extracting(TargetView::name)
        .isEqualTo("KingTower_1_0");
    // Every Skeleton made before the tower fell forgot it at the same cleanup.
    for (Made earlier : made) {
      if (earlier.tick() < fell) {
        assertThat(earlier.unit().getUnit().selection().getRegisteredTowers())
            .as("a Skeleton made on tick " + earlier.tick())
            .extracting(TargetView::name)
            .doesNotContain(DESTROYED);
      }
    }
  }
}
