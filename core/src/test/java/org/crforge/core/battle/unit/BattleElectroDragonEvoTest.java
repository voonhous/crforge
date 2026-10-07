package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ChainProjectileAttack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Electro Dragon: its attack sequence of one entry runs a chain projectile attack in
 * place of the launch, which hops from the hit's target to the nearest enemy within ChainRange of
 * where that target stands, centre to centre.
 */
class BattleElectroDragonEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Side 1's right princess tower stands here. */
  private static final int TOWER_X = 14500;

  private static final int TOWER_Y = 25500;

  @Test
  @DisplayName(
      "each attack's chain launches one hop at the Musketeer and ends after one empty search: the"
          + " princess tower beyond ChainRange of the hop's point is not chained")
  void aChainEndsWhenNobodyStandsWithinItsRange() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity dragon =
        match.deploy(0, GameData.unit("electro_dragon_ev1"), LEVEL, 0, TOWER_X, 17000);
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer"), LEVEL, 1, TOWER_X, TOWER_Y - 4001);
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
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity dragon =
        match.deploy(0, GameData.unit("electro_dragon_ev1"), LEVEL, 0, 3500, 17000);
    CharacterEntity musketeer = match.deploy(0, GameData.unit("Musketeer"), LEVEL, 1, 3500, 21000);
    CharacterEntity giant = match.deploy(0, GameData.unit("Giant"), LEVEL, 1, 3500, 22000);
    Map<ChainProjectileAttack.Run, List<Integer>> runs = observe(battle, dragon, 120);

    ChainProjectileAttack.Run first = runs.keySet().iterator().next();
    assertThat(first.getHops()).as("hops past the first").isGreaterThanOrEqualTo(3);
    assertThat(first.remembered())
        .as("at most two remembered, the Musketeer the first target")
        .hasSizeLessThanOrEqualTo(2)
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
