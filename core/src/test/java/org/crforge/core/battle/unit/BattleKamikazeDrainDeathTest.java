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
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the last step of a Kamikaze unit's drain kills it within its tick. The game hands each step
 * of the drain to the damage entry, which queues it for the holder's damage drain after every
 * post-hook, as it queues a buff's damage over time; so the unit dies, and its death spawn is made,
 * only after every state visit of the tick, and a spawner listed after it that fires on that tick
 * makes its child first.
 *
 * <p>The scene: side 0's Skeleton Barrel beside the top side's left princess tower, which it hits
 * at once, and a Tombstone for side 0 listed after it that makes one Skeleton every visit. The
 * scene writes every column its outcome is read from - the barrel's hit, drain and death spawn, the
 * Tombstone's spawner - so its ticks are its own and not a version's.
 */
class BattleKamikazeDrainDeathTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** One child made in the scene: the tick, its row and its id. */
  private record Made(int tick, String row, int id) {}

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "SkeletonBalloon")
                .put("Kamikaze", true)
                .put("KamikazeTime", 500)
                .put("HitSpeed", 300)
                .put("LoadTime", 200)
                .put("Range", 350)
                .put("DeployTime", 1000)
                .put("DeathSpawnCharacter", "SkeletonContainerNew")
                .put("DeathSpawnCount", 1));
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
      "the drain step that kills a Kamikaze unit lands at the damage drain, so a spawner listed"
          + " after it makes its child before the unit's death spawn")
  void theSpawnersChildComesFirst(@TempDir Path folder) throws IOException {
    GameTables tables = written(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<Made> made = new ArrayList<>();
    List<Integer> drainTicks = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int t, SpawnHost source, CharacterEntity child, int x, int y) {
                made.add(new Made(t, child.getData().name(), child.getId()));
              }

              @Override
              public void kamikazeDrained(
                  int t, WorldEntity unit, int damage, int before, DamageResult result) {
                drainTicks.add(t);
              }
            });
    CharacterEntity barrel =
        match.deploy(
            0, match.getWorld().getRecords().unit("SkeletonBalloon"), LEVEL, 0, 3500, 23730);
    CharacterEntity tombstone =
        match.deploy(0, match.getWorld().getRecords().unit("Tombstone"), LEVEL, 0, 9000, 8000);
    for (int i = 0; i < 80; i++) {
      match.getBattle().step();
    }
    assertThat(tombstone.getId()).as("listed after the barrel").isGreaterThan(barrel.getId());
    Made container =
        made.stream().filter(m -> m.row().equals("SkeletonContainerNew")).findFirst().orElseThrow();
    assertThat(drainTicks).as("the barrel drained to its death").contains(container.tick());
    List<Made> sameTick =
        made.stream()
            .filter(m -> m.tick() == container.tick() && m.row().equals("Skeleton"))
            .toList();
    assertThat(sameTick).as("the Tombstone fired on the barrel's last tick").hasSize(1);
    assertThat(sameTick.get(0).id())
        .as("the Tombstone's child is made before the barrel's death spawn")
        .isLessThan(container.id());
  }
}
