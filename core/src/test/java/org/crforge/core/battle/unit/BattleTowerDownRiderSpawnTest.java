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
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the side list of princess towers for a unit made inside the cleanup that removes a princess
 * tower, after the tower's removal: a Goblin Giant dying on the tick the top side's left princess
 * tower is destroyed leaves the battle after the tower in that tick's cleanup, its riders are let
 * go there and each leaves its death spawn, a Spear Goblin. The side list is one list per side,
 * which the tower has already left when the Spear Goblins are made, so they never find it and walk
 * at the next default target.
 *
 * <p>The scene: the towers passive; side 0's Goblin Giant, with two riders that each leave one
 * Spear Goblin, walking up the left lane; the tower and the Giant are both killed in the same tick.
 * The scene writes every column its outcome is read from, so its ticks are its own and not a
 * version's.
 */
class BattleTowerDownRiderSpawnTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The top side's left princess tower, the one destroyed. */
  private static final String DESTROYED = "PrincessTower_1_1";

  /** The riders the Giant's row attaches. */
  private static final int RIDERS = 2;

  /** The steps the Giant walks before the tower and the Giant are killed. */
  private static final int WALK = 60;

  /** The steps the scene runs after the kills, enough for the Spear Goblins to walk. */
  private static final int AFTER = 120;

  /** The configured tables with the Giant's riders and their death spawns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "GoblinGiant")
              .put("SpawnCharacter", "SpearGoblinGiant")
              .put("SpawnNumber", RIDERS);
          GameData.columns(rows, "SpearGoblinGiant")
              .put("DeathSpawnCharacter", "SpearGoblin")
              .put("DeathSpawnCount", 1);
          GameData.columns(rows, "SpearGoblin").put("SightRange", 1000);
        });
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> GameData.columns(rows, "GoblinGiant").put("SummonNumber", 1));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a death spawn made in the cleanup that removes a princess tower, after the tower, never"
          + " finds the tower in its side list and walks at the king")
  void aDeathSpawnMadeAfterTheTowersRemovalNeverFindsIt(@TempDir Path folder) throws IOException {
    GameTables tables = written(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.play(0, records.card("GoblinGiant"), LEVEL, 0, 3500, 14000, "GoblinGiant");
    List<CharacterEntity> spawns = new ArrayList<>();
    int[] at = {0};
    int[] madeAt = {-1};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity spawned, int x, int y) {
                if (source instanceof CharacterEntity rider
                    && rider.getData().name().equals("SpearGoblinGiant")) {
                  spawns.add(spawned);
                  madeAt[0] = at[0];
                }
              }
            });
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), DESTROYED);
    for (int step = 0; step < WALK; step++) {
      at[0] = step;
      match.getBattle().step();
    }
    CharacterEntity giant = match.getPlays().get(0).units().get(0);
    assertThat(giant.riders()).hasSize(RIDERS);
    assertThat(tower.getId())
        .as("the tower is listed ahead of the Giant, so the cleanup removes it first")
        .isLessThan(giant.getId());

    // Both kills are queued for the next step's damage drain: the tower and the Giant die in that
    // step and leave in its closing cleanup, the tower first; the Giant's riders are let go after
    // the tower has left and each leaves its Spear Goblin there.
    match.getWorld().kill(tower, null);
    match.getWorld().kill(giant, null);
    int fell = WALK;
    at[0] = fell;
    match.getBattle().step();
    assertThat(tower.getTargetView().alive()).as("the tower is destroyed").isFalse();
    assertThat(spawns).as("each rider left its Spear Goblin").hasSize(RIDERS);
    assertThat(madeAt[0]).as("the Spear Goblins are made on the tower's last step").isEqualTo(fell);
    for (CharacterEntity spawn : spawns) {
      assertThat(spawn.getUnit().selection().getRegisteredTowers())
          .as("the side list " + spawn.name() + " reads holds no destroyed tower")
          .extracting(TargetView::name)
          .doesNotContain(DESTROYED);
    }

    for (int step = 0; step < AFTER; step++) {
      at[0] = fell + 1 + step;
      match.getBattle().step();
    }
    // With the left princess tower gone, the one left stands in the other lane and is passed over:
    // each Spear Goblin walks at the king.
    for (CharacterEntity spawn : spawns) {
      assertThat(spawn.getUnit().targeting().getReference())
          .as("the reference of " + spawn.name() + " once it walks")
          .isNotNull()
          .extracting(TargetView::name)
          .isEqualTo("KingTower_1_0");
    }
  }
}
