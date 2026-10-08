package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Royal Chef's cooking (ChefTower_CookingAction of data version 16.402.18) once a princess
 * tower of its side is destroyed. The cooking reads its side's list of princess towers every step,
 * and a destroyed tower leaves that list in the cleanup that takes it out of the battle, so from
 * the next step it adds nothing: the bar gains the baseline 600 and 200 for the idle tower left,
 * 800 a step instead of 1000, and the destroyed contribution (0 in the row) for the tower short of
 * two. With both towers gone the cooking ends and nothing more is thrown.
 */
class ChefCookingTowerLostTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String PANCAKE = "ChefTower_pancake_projectile";

  /** The Royal Chef's towers on side 1, passive like side 0's, so each tower stays idle. */
  private static Standard1v1Battle battle(GameTables tables) {
    return new Standard1v1Battle(
        tables,
        List.of(
            new Standard1v1Battle.Towers(Standard1v1Battle.PRINCESS_TOWERS, LEVEL, LEVEL),
            new Standard1v1Battle.Towers("King_ChefTowers", LEVEL, LEVEL)),
        false);
  }

  @Test
  @DisplayName(
      "a destroyed princess tower adds nothing from the step after it leaves: with the other tower"
          + " idle the bar fills at 800 a step, so a pancake follows the last after 581 steps"
          + " instead of 466")
  void aDestroyedTowerAddsNothing() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    // A friendly Giant behind the left tower for every pancake to go to.
    battle.play(200, records.card("Giant"), LEVEL, 1, 3500, 29000, "g");
    List<Integer> thrown = new ArrayList<>();
    Set<ProjectileEntity> seen = new HashSet<>();
    TowerEntity right = chefTower(battle, 14500);
    for (int tick = 1; tick <= 2900; tick++) {
      if (tick == 1200) {
        // After the second pancake: the right tower dies, and leaves at that step's cleanup.
        battle.getWorld().kill(right, null);
      }
      step(battle, tick);
      recordPancakes(battle, seen, thrown);
    }
    assertThat(battle.getWorld().getHolder().entities()).doesNotContain(right);
    // Both towers idle: 460 steps of 1000, the attempt and the five steps of the throw.
    assertThat(thrown.get(1) - thrown.get(0)).as("%s", thrown).isEqualTo(466);
    assertThat(thrown.get(1)).isLessThan(1200);
    // The cooking toward the third pancake starts at 1000 a step and goes on at 800 once the right
    // tower is gone; the pancakes after it are 575 steps of 800 and the same six steps apart.
    assertThat(thrown.get(2) - thrown.get(1)).isBetween(467, 580);
    assertThat(thrown.get(3) - thrown.get(2)).isEqualTo(581);
    assertThat(thrown.get(4) - thrown.get(3)).isEqualTo(581);
  }

  @Test
  @DisplayName("with both princess towers destroyed the cooking ends and no pancake follows")
  void bothTowersDestroyedEndTheCooking() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    battle.play(200, records.card("Giant"), LEVEL, 1, 3500, 29000, "g");
    List<Integer> thrown = new ArrayList<>();
    Set<ProjectileEntity> seen = new HashSet<>();
    TowerEntity left = chefTower(battle, 3500);
    TowerEntity right = chefTower(battle, 14500);
    for (int tick = 1; tick <= 2000; tick++) {
      if (tick == 700) {
        battle.getWorld().kill(right, null);
        battle.getWorld().kill(left, null);
      }
      step(battle, tick);
      recordPancakes(battle, seen, thrown);
    }
    assertThat(thrown).hasSize(1).first().matches(tick -> tick < 700);
  }

  /** Side 1's princess-slot tower at a point across the arena. */
  private static TowerEntity chefTower(Standard1v1Battle battle, int x) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(tower -> tower.getData().name().equals("ChefTower"))
        .filter(tower -> tower.getView().getX() == x)
        .findFirst()
        .orElseThrow();
  }

  /** Adds the tick of every pancake not seen before. */
  private static void recordPancakes(
      Standard1v1Battle battle, Set<ProjectileEntity> seen, List<Integer> thrown) {
    for (var entity : battle.getWorld().getHolder().entities()) {
      if (entity instanceof ProjectileEntity projectile
          && projectile.getData().name().equals(PANCAKE)
          && seen.add(projectile)) {
        thrown.add(battle.getBattle().getTick());
      }
    }
  }

  /** Steps the battle until its tick is the one given. */
  private static void step(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
