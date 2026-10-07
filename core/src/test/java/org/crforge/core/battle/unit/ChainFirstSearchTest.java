package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ChainProjectileAttack;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Electro Dragon's chain of data version 16.402.18 (electro_dragon_ev1_attack, an
 * ActionChainProjectileAttack): a run's hop timer starts stopped, so its first next-target search
 * waits, like every later one, until the first hop's projectile has gone. The run then searches on
 * the step it finds that projectile gone, and the second hop flies from where the first target
 * stood. The game of 14.593.1 starts the timer at 0 and searches on the step after the first hop,
 * while its projectile still flies (BattleElectroDragonEvoTest).
 */
class ChainFirstSearchTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the dragon to deploy and chain at least once. */
  private static final int TICKS = 120;

  @Test
  @DisplayName(
      "the second hop is launched on the step after the first hop's projectile has gone, never"
          + " while it flies")
  void theFirstSearchWaitsForTheFirstHopToLand() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity dragon =
        match.deploy(0, records.unit("electro_dragon_ev1"), LEVEL, 0, 3500, 17000, "dragon");
    match.deploy(0, records.unit("Musketeer"), LEVEL, 1, 3500, 21000, "musketeer");
    match.deploy(0, records.unit("Giant"), LEVEL, 1, 3500, 22000, "giant");

    // Per run, per step: the hops launched and whether the first hop's projectile is still listed.
    Map<ChainProjectileAttack.Run, List<int[]>> runs = new LinkedHashMap<>();
    for (int tick = 0; tick < TICKS; tick++) {
      battle.step();
      ActionHolder holder = dragon.actionHolder();
      for (ActionInstance instance : new ArrayList<>(holder.running())) {
        if (instance instanceof ChainProjectileAttack.Run run && !run.launched().isEmpty()) {
          boolean firstLive = match.getWorld().liveObject(run.launched().get(0)) != null;
          runs.computeIfAbsent(run, r -> new ArrayList<>())
              .add(new int[] {tick, run.getHops(), firstLive ? 1 : 0});
        }
      }
    }

    assertThat(runs).as("the dragon attacked through its chain").isNotEmpty();
    List<int[]> steps = runs.values().iterator().next();
    int landed = -1;
    for (int[] step : steps) {
      if (step[2] == 1) {
        assertThat(step[1]).as("hops on step %d, the first hop still flying", step[0]).isEqualTo(1);
      } else if (landed < 0) {
        landed = step[0];
        assertThat(step[1])
            .as("hops on step %d, the first hop's projectile just gone", step[0])
            .isEqualTo(1);
      }
    }
    assertThat(landed).as("the first hop's projectile went").isGreaterThanOrEqualTo(0);
    int finalLanded = landed;
    int[] next = steps.stream().filter(s -> s[0] == finalLanded + 1).findFirst().orElseThrow();
    assertThat(next[1]).as("the second hop, on the step after the first landed").isEqualTo(2);
  }
}
