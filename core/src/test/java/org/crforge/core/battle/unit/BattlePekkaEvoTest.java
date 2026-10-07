package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Pekka: each unit it kills runs its killed-done action, which picks one of three heal
 * buffs by the killed unit's hit points at card level 11 (below 990, below 1990, or more), and the
 * heal may lift it above its maximum, up to half as much again. Its resurrect columns only show the
 * kill (a soul flying to it), so the unit is built with them.
 */
class BattlePekkaEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** An evolved Pekka of side 0 at (9000, 10000) facing a still unit of side 1 just ahead. */
  private static CharacterEntity[] facing(Standard1v1Battle match, String row) {
    CharacterEntity pekka =
        match.deploy(
            0, match.getWorld().getRecords().unit("Pekka_EV1"), LEVEL, 0, 9000, 10000, "pekka");
    CharacterEntity target =
        match.deploy(0, match.getWorld().getRecords().unit(row), LEVEL, 1, 9000, 12500, "target");
    target.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    return new CharacterEntity[] {pekka, target};
  }

  /** Steps until the unit is dead, at most the given ticks; answers the steps taken. */
  private static int stepUntilDead(Standard1v1Battle match, CharacterEntity unit, int ticks) {
    int steps = 0;
    while (steps < ticks && unit.getView().isAlive()) {
      match.getBattle().step();
      steps++;
    }
    assertThat(unit.getView().isAlive()).as(unit.name() + " killed").isFalse();
    return steps;
  }

  /** The most steps a heal is waited for. */
  private static final int SOUL_TICKS = 40;

  /**
   * The steps from the one that kills to the one whose end finds the heal buff on the Pekka: the
   * kill sends a soul to it (PekkaEV1_SoulDrain), whose flight of 700 ms is 14 steps of 50 ms, and
   * its arrival lists the heal buff for 50 ms.
   */
  private static final int SOUL_STEPS = 14;

  /**
   * Steps until the unit carries the buff, at most the given ticks; answers the steps taken, or -1
   * when it never does.
   */
  private static int stepsUntilCarried(
      Standard1v1Battle match, CharacterEntity unit, String buff, int ticks) {
    int steps = 0;
    while (steps < ticks && !unit.getBuffs().carries(buff)) {
      match.getBattle().step();
      steps++;
    }
    return unit.getBuffs().carries(buff) ? steps : -1;
  }

  @Test
  @DisplayName("a kill of a Knight (1766 at level 11) gives the middle heal")
  void aKnightGivesTheMiddleHeal() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity[] units = facing(match, "Knight");
    stepUntilDead(match, units[1], 400);

    assertThat(stepsUntilCarried(match, units[0], "PekkaEV1_HealMed", SOUL_TICKS))
        .isEqualTo(SOUL_STEPS);
    assertThat(units[0].getBuffs().carries("PekkaEV1_HealMin")).isFalse();
    assertThat(units[0].getBuffs().carries("PekkaEV1_HealMax")).isFalse();
  }

  @Test
  @DisplayName("a kill of a Skeleton (81 at level 11) gives the least heal")
  void aSkeletonGivesTheLeastHeal() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity[] units = facing(match, "Skeleton");
    stepUntilDead(match, units[1], 400);

    assertThat(stepsUntilCarried(match, units[0], "PekkaEV1_HealMin", SOUL_TICKS))
        .isEqualTo(SOUL_STEPS);
    assertThat(units[0].getBuffs().carries("PekkaEV1_HealMed")).isFalse();
  }

  @Test
  @DisplayName(
      "a kill of a weakened Giant (above 1990 at level 11, whatever it has left) gives the most heal,"
          + " which lifts a Pekka at full hit points above its maximum but not past half again")
  void aGiantGivesTheMostHealAndAnOverheal() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity[] units = facing(match, "Giant");
    CharacterEntity giant = units[1];
    // Leave the Giant one hit from death, so the Pekka is untouched when it kills it.
    match
        .getWorld()
        .dealDamage(giant.getTargetView(), giant.getHitPoints().getHitPoints() - 1, 0, 1);
    giant.setActive(CharacterEntity.TARGETING_SLOT, false);
    stepUntilDead(match, giant, 400);

    HitPoints hitPoints = units[0].getHitPoints();
    assertThat(hitPoints.getHitPoints()).isEqualTo(hitPoints.getMaximum());
    assertThat(stepsUntilCarried(match, units[0], "PekkaEV1_HealMax", SOUL_TICKS))
        .isEqualTo(SOUL_STEPS);
    for (int i = 0; i < 20; i++) {
      match.getBattle().step();
    }
    assertThat(hitPoints.getHitPoints())
        .isGreaterThan(hitPoints.getMaximum())
        .isLessThanOrEqualTo(hitPoints.getMaximum() * 3 / 2);
  }
}
