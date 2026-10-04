package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BurstAttack;
import org.junit.jupiter.api.Test;

/**
 * The towers a battle is built with: each side's from the spawn group of its tower selection, the
 * king row at the king's level and every other row at the selection's level.
 */
class Standard1v1BattleTowersTest {

  @Test
  void buildsEachSidesTowersFromItsOwnSpawnGroup() {
    // Side 0 the princess towers at level 1, side 1 the cannoneer towers: its king at level 1 and
    // its Cannoneer rows at level 6, five steps above the first.
    Standard1v1Battle battle =
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
                new Standard1v1Battle.Towers("King_CannonTowers", 1, 6)),
            true);

    // Created side by side, king first, then the low and the high princess slot; the top side is
    // the bottom side mirrored along the arena's length.
    assertThat(towers(battle))
        .extracting(
            tower -> tower.getData().name(),
            WorldEntity::side,
            WorldEntity::x,
            WorldEntity::y,
            WorldEntity::level,
            tower -> tower.getHitPoints().getHitPoints())
        .containsExactly(
            tuple("KingTower", 0, 9000, 3000, 1, 2400),
            tuple("PrincessTower", 0, 3500, 6500, 1, 1400),
            tuple("PrincessTower", 0, 14500, 6500, 1, 1400),
            tuple("KingTower", 1, 9000, 29000, 1, 2400),
            tuple("Cannoneer", 1, 3500, 25500, 6, 1740),
            tuple("Cannoneer", 1, 14500, 25500, 6, 1740));
  }

  @Test
  void theDefaultTowersAreThePrincessTowersOfBothSidesAtOneLevel() {
    Standard1v1Battle byLevel = new Standard1v1Battle(GameData.tables(), 3);
    Standard1v1Battle bySpawnGroup =
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers("King_PrincessTowers", 3, 3),
                new Standard1v1Battle.Towers("King_PrincessTowers", 3, 3)),
            true);

    assertThat(describe(byLevel)).isEqualTo(describe(bySpawnGroup));
  }

  @Test
  void aDaggerDuchessStartsItsChargeCounterFullOnItsFirstStep() {
    Standard1v1Battle battle =
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
                new Standard1v1Battle.Towers("King_KnifeTowers", 1, 9)),
            true);

    battle.getBattle().step();

    // Each Duchess's placement queued its row's charge counter, which the first step starts with
    // all eight charges; at eight the counter sets the index the list gives last, entry 0.
    List<WorldEntity> duchesses =
        towers(battle).stream()
            .filter(tower -> tower.getData().name().equals("DaggerDuchess"))
            .toList();
    assertThat(duchesses).hasSize(2);
    for (WorldEntity duchess : duchesses) {
      List<BurstAttack.Run> runs =
          duchess.actionHolder().running().stream()
              .filter(BurstAttack.Run.class::isInstance)
              .map(BurstAttack.Run.class::cast)
              .toList();
      assertThat(runs).hasSize(1);
      assertThat(runs.get(0).charges()).isEqualTo(8);
      assertThat(runs.get(0).depleted()).isFalse();
      assertThat(duchess.attackSequenceIndex()).isZero();
    }
  }

  private static List<String> describe(Standard1v1Battle battle) {
    List<String> lines = new ArrayList<>();
    for (WorldEntity tower : towers(battle)) {
      lines.add(
          tower.getId()
              + " "
              + tower.getData().name()
              + " "
              + tower.side()
              + " "
              + tower.x()
              + ","
              + tower.y()
              + " level "
              + tower.level()
              + " hp "
              + tower.getHitPoints().getHitPoints());
    }
    return lines;
  }

  private static List<WorldEntity> towers(Standard1v1Battle battle) {
    List<WorldEntity> towers = new ArrayList<>();
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      towers.add((WorldEntity) entity);
    }
    return towers;
  }
}
