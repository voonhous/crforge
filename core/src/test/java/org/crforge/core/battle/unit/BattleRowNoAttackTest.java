package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A unit whose own row sets NO_ATTACK carries it in its tag word from the start, where every reader
 * of the tag finds it: the targeting visit clears its attack each step, so it never hits. The Elite
 * Archer hero's decoy of data version 16.402.18 sets it. Each scene alters the configured Knight to
 * set it and plays it into an enemy Knight.
 */
class BattleRowNoAttackTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hits each side's Knight deals, by the dealer's side. */
  private static List<Integer> hitsBy(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<Integer> out = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target.name().startsWith("Red") || target.name().startsWith("Blue")) {
                  out.add(target.side() == 0 ? 1 : 0);
                }
              }
            });
    battle.play(1, battle.getWorld().getRecords().card("Knight"), LEVEL, 0, 3500, 15000, "Blue");
    battle.play(1, battle.getWorld().getRecords().card("Knight"), LEVEL, 1, 3500, 17500, "Red");
    while (battle.getBattle().getTick() <= 300) {
      battle.getBattle().step();
    }
    return out;
  }

  @Test
  @DisplayName("Knights whose row sets NO_ATTACK meet and never hit each other")
  void aRowNoAttackNeverHits(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> GameData.columns(rows, "Knight").put("GameTagsToSet", "NO_ATTACK"));

    // The plain Knights fight; the altered ones never deal a hit.
    assertThat(hitsBy(GameData.tables())).contains(0, 1);
    assertThat(hitsBy(tables)).isEmpty();
  }
}
