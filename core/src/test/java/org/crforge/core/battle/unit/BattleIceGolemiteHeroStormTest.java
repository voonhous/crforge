package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The damage circle the Ice Golemite hero form's ability spawns on itself: a circle of radius 4000
 * that follows the Golemite and, every 1500 ms from its first update, queues its level-scaled
 * damage as a typed hit on every enemy it reaches, a crown tower taking only its 5 percent share,
 * rounded up. Its life ends on its 61st update, after its third hit, with an action that spawns the
 * freeze circle, whose one hit gives each enemy it reaches the freeze buff its row and radius
 * choose.
 */
class BattleIceGolemiteHeroStormTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The steps the twin battles run before the spawn: every unit has deployed. */
  private static final int SETTLE = 40;

  @Test
  @DisplayName(
      "the damage circle hits an enemy Knight and an enemy princess tower on its first update and"
          + " every 30 steps after, the tower taking its share; its life end freezes them")
  void theStormHitsEveryThirtySteps() {
    Twin storm = new Twin();
    Twin quiet = new Twin();
    for (int k = 0; k < SETTLE; k++) {
      storm.match.getBattle().step();
      quiet.match.getBattle().step();
    }
    BattleAction spawn =
        GameData.actions()
            .build(
                "IceGolemiteHero_Spawn_Damage_AEO", storm.match.getWorld().binding(storm.golemite));
    storm.golemite.actionHolder().start(spawn);
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            16,
            storm.golemite.getPackedLevel(),
            ScalingMode.CARD_DAMAGE,
            RarityTable.COMMON);
    // A crown tower takes 5 percent of it, rounded up: 2 of 40 at this level.
    int share = (5 * damage + 99) / 100;
    assertThat(List.of(damage, share)).containsExactly(40, 2);

    // Follow both for 59 steps: the third hit falls on the 61st update, with the life end.
    int first = -1;
    int[] knightLost = new int[60];
    int[] towerLost = new int[60];
    for (int k = 1; k < 60; k++) {
      storm.match.getBattle().step();
      quiet.match.getBattle().step();
      knightLost[k] =
          quiet.knight.getHitPoints().getHitPoints() - storm.knight.getHitPoints().getHitPoints();
      towerLost[k] =
          quiet.tower.getHitPoints().getHitPoints() - storm.tower.getHitPoints().getHitPoints();
      if (first == -1 && towerLost[k] != 0) {
        first = k;
      }
    }
    // Spawned between two steps, it is admitted at the next step's first cleanup and updated in
    // that step's post-hooks, its first hit queued there and dealt after them.
    assertThat(first).isEqualTo(1);
    for (int k = 1; k < 60; k++) {
      int hits = k < first ? 0 : k < first + 30 ? 1 : 2;
      assertThat(towerLost[k]).as("tower, step %d", k).isEqualTo(hits * share);
      assertThat(knightLost[k]).as("knight, step %d", k).isEqualTo(hits * damage);
    }
    // The 61st update hits a third time and ends the life, spawning the freeze circle; that one is
    // first updated in the next step, its one hit choosing a freeze buff for each object it
    // reaches.
    int last = first + 60;
    for (int k = 60; k <= last; k++) {
      storm.match.getBattle().step();
    }
    assertThat(freezes(storm.tower)).isEmpty();
    storm.match.getBattle().step();
    assertThat(freezes(storm.tower)).containsExactly("IceGolemiteHero_Freeze_Buff_Tower");
    assertThat(freezes(storm.knight)).containsExactly("IceGolemiteHero_Freeze_Buff_Small");
    assertThat(freezes(storm.golemite)).isEmpty();
  }

  /** The names of the Ice Golemite hero freeze buffs an entity carries, in its list's order. */
  private static List<String> freezes(WorldEntity entity) {
    return entity.getBuffs().items().stream()
        .map(instance -> instance.getBuff().name())
        .filter(name -> name.startsWith("IceGolemiteHero_Freeze"))
        .toList();
  }

  /** One battle: a side-0 Ice Golemite hero form, an enemy Knight beside it, towers that hold. */
  private static final class Twin {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final CharacterEntity golemite =
        match.deploy(0, GameData.unit("IceGolemiteHero"), LEVEL, 0, 3500, 22000, "golemite");
    final CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 5000, 21500, "knight");
    final TowerEntity tower =
        BattleMusketeerRunTest.towerNamed(match.getBattle(), "PrincessTower_1_1");
  }
}
