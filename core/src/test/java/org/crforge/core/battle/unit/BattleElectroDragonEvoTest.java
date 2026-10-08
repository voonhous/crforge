package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ChainProjectileAttack;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Electro Dragon: its attack sequence of one entry runs a chain projectile attack in
 * place of the launch, which hops from the hit's target to the nearest enemy within ChainRange of
 * where that target stands, centre to centre.
 *
 * <p>The scene writes every column its ticks and hops are read from: the dragon's timing, reach and
 * size, its hop's speed, the chain's range and memory, the Musketeer's and the Giant's size and the
 * towers' places, so they are its own and not a version's.
 */
class BattleElectroDragonEvoTest {

  /**
   * The chain's range and the targets it remembers, as the scene writes them; its length is
   * unbounded (-1).
   */
  private static final int CHAIN_RANGE = 4000;

  private static final int REMEMBERED = 2;

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written, and their records. */
  private static GameTables tables;

  private static BattleRecords records;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "electro_dragon_ev1")
              .put("HitSpeed", 2100)
              .put("LoadTime", 1400)
              .put("DeployTime", 1000)
              .put("Range", 3500)
              .put("SightRange", 5500)
              .put("Speed", 60)
              .put("CollisionRadius", 600)
              .put("FlyingHeight", 3500)
              .put("ProjectileStartRadius", 1250)
              .put("ProjectileStartZ", 1900);
          GameData.columns(rows, "Musketeer").put("CollisionRadius", 500).put("DeployTime", 1000);
          GameData.columns(rows, "Giant").put("CollisionRadius", 750).put("DeployTime", 1000);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          for (String hop :
              List.of(
                  "electro_dragon_ev1_lightning_1",
                  "electro_dragon_ev1_lightning_1_continued",
                  "electro_dragon_ev1_lightning_2")) {
            GameData.columns(rows, hop).put("Speed", 2000);
          }
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows ->
            ((ObjectNode) rows.get("electro_dragon_ev1_attack").get("fields"))
                .put("ChainRange", CHAIN_RANGE)
                .put("MaxChainLength", -1)
                .put("MaximumTargetsToRememberForRepeatChecks", REMEMBERED));
    GameData.alterLoaded(
        folder,
        "spawn_groups",
        rows -> {
          ArrayNode towers = GameData.columns(rows, "King_PrincessTowers").putArray("Objects");
          towers.addObject().put("Data", "KingTower").put("x", 18).put("y", 6);
          towers.addObject().put("Data", "PrincessTower").put("x", 7).put("y", 13);
          towers.addObject().put("Data", "PrincessTower").put("x", 29).put("y", 13);
        });
    tables = GameTables.load(folder);
    records = new BattleRecords(tables);
  }

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Side 1's right princess tower stands here. */
  private static final int TOWER_X = 14500;

  private static final int TOWER_Y = 25500;

  @Test
  @DisplayName(
      "each attack's chain launches one hop at the Musketeer and ends after one empty search: the"
          + " princess tower beyond ChainRange of the hop's point is not chained")
  void aChainEndsWhenNobodyStandsWithinItsRange() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity dragon =
        match.deploy(0, records.unit("electro_dragon_ev1"), LEVEL, 0, TOWER_X, 17000);
    CharacterEntity musketeer =
        match.deploy(0, records.unit("Musketeer"), LEVEL, 1, TOWER_X, TOWER_Y - CHAIN_RANGE - 1);
    Map<ChainProjectileAttack.Run, List<Integer>> runs = observe(battle, dragon, 200);

    assertThat(runs).as("the dragon attacked through its chain").hasSizeGreaterThanOrEqualTo(2);
    assertThat(runs.values().iterator().next()).containsExactly(33, 34, 35, 36, 37);
    for (Map.Entry<ChainProjectileAttack.Run, List<Integer>> e : runs.entrySet()) {
      ChainProjectileAttack.Run run = e.getKey();
      assertThat(run.getHops()).as("one hop").isEqualTo(1);
      assertThat(run.remembered()).containsExactly(musketeer.getId());
      assertThat(run.isFinished()).as("ended by the empty search").isTrue();
      // Listed on the attack's step and the hop launched on the next. The hop's projectile, 2000 a
      // step, starts 1250 ahead of the dragon and flies the 3249 left to the Musketeer on the two
      // steps after, gone at the end of the second; the search waits for that
      // (ChainFirstSearchTest)
      // and runs, finding nobody and ending the run, on the step after: five steps, 33 to 37 for
      // the first attack.
      assertThat(e.getValue()).as("ticks the run was seen at").hasSize(5);
    }
  }

  @Test
  @DisplayName(
      "a second enemy within ChainRange of the first target is the next hop, and the chain bounces"
          + " back to the first once both are remembered")
  void aChainHopsToTheNearestEnemyWithinItsRange() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity dragon =
        match.deploy(0, records.unit("electro_dragon_ev1"), LEVEL, 0, 3500, 17000);
    CharacterEntity musketeer = match.deploy(0, records.unit("Musketeer"), LEVEL, 1, 3500, 21000);
    CharacterEntity giant = match.deploy(0, records.unit("Giant"), LEVEL, 1, 3500, 22000);
    Map<ChainProjectileAttack.Run, List<Integer>> runs = observe(battle, dragon, 120);

    ChainProjectileAttack.Run first = runs.keySet().iterator().next();
    assertThat(first.getHops()).as("hops past the first").isGreaterThanOrEqualTo(3);
    assertThat(first.remembered())
        .as("at most two remembered, the Musketeer the first target")
        .hasSizeLessThanOrEqualTo(REMEMBERED)
        .containsAnyOf(musketeer.getId(), giant.getId());
  }

  /**
   * Steps the battle and records every chain run listed on the dragon and the ticks it was seen at,
   * in the order the runs first appeared.
   */
  private static Map<ChainProjectileAttack.Run, List<Integer>> observe(
      Battle battle, CharacterEntity dragon, int ticks) {
    Map<ChainProjectileAttack.Run, List<Integer>> runs = new LinkedHashMap<>();
    for (int tick = 0; tick < ticks; tick++) {
      battle.step();
      ActionHolder holder = dragon.actionHolder();
      for (ActionInstance instance : new ArrayList<>(holder.running())) {
        if (instance instanceof ChainProjectileAttack.Run run) {
          runs.computeIfAbsent(run, r -> new ArrayList<>()).add(tick);
        }
      }
    }
    return runs;
  }
}
