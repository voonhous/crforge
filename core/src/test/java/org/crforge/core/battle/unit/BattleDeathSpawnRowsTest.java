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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A building whose row sets a second death spawn row: the Goblin Party Hut, whose lifetime ends it,
 * leaves three Spear Goblins and then one Goblin Brawler on one ring of 1500 around it. The ring is
 * divided by all four children: the Spear Goblins stand at 180, 90 and 0 degrees from the row's
 * angle shift of 0, and the Brawler half a step past the opposite side, at 225 degrees.
 */
class BattleDeathSpawnRowsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** One child of the hut's death spawn: its row and where it was made. */
  private record Child(String row, int x, int y) {}

  @Test
  @DisplayName(
      "the hut's two death spawn rows share one ring divided by both counts, the second row"
          + " made after the first")
  void twoRowsShareOneRing(@TempDir Path folder) throws IOException {
    // The hut's death spawn columns, and a lifetime that ends it within the scene, written.
    GameTables tables =
        GameData.altered(
            folder,
            "buildings",
            rows ->
                GameData.columns(rows, "GoblinPartyHut")
                    .put("LifeTime", 30000)
                    .put("DeathSpawnCharacter", "SpearGoblin")
                    .put("DeathSpawnCount", 3)
                    .put("DeathSpawnCharacter2", "GoblinBrawler")
                    .put("DeathSpawnCount2", 1)
                    .put("DeathSpawnRadius", 1500)
                    .put("SpawnAngleShift", 0));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<Child> children = new ArrayList<>();
    int[] hut = new int[2];
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
                if (source instanceof WorldEntity dying
                    && dying.getData().name().equals("GoblinPartyHut")) {
                  hut[0] = dying.getView().getX();
                  hut[1] = dying.getView().getY();
                  children.add(new Child(child.getData().name(), createdX, createdY));
                }
              }
            });
    battle.play(
        1, battle.getWorld().getRecords().card("GoblinPartyHut"), LEVEL, 0, 3500, 10000, "Hut");
    while (battle.getBattle().getTick() <= 900 && children.isEmpty()) {
      battle.getBattle().step();
    }

    assertThat(children)
        .containsExactly(
            new Child("SpearGoblin", hut[0] - 1500, hut[1]),
            new Child("SpearGoblin", hut[0], hut[1] + 1500),
            new Child("SpearGoblin", hut[0] + 1500, hut[1]),
            new Child("GoblinBrawler", hut[0] - 1060, hut[1] - 1060));
  }
}
