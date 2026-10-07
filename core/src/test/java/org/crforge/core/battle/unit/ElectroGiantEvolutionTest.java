package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.CountingRun;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Electro Giant's pulse timer (ElectroGiant_EV1 of data version 16.402.18): its
 * starting group lists ElectroGiant_EV1_Pulse_Attack_Interval, an interval of 6000 ms whose counter
 * starts at 2500 and which follows its owner's hit speed. Each step the counter loses half of what
 * the owner's buffs make of a hit speed of 100, read afresh on that step: 50 with no buff, 65 under
 * Rage.
 *
 * <p>Each pulse puts ElectroGiant_EV1_Pulse_Debuff on every object it hits that is not a crown
 * tower (`!is_crown_tower`): listed, its start action lowers the carrier's level by one; refreshed
 * by the next pulse, its stacked action lowers it once more. A level that falls takes the hit
 * points down to their share of the new maximum.
 */
class ElectroGiantEvolutionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String INTERVAL = "ElectroGiant_EV1_Pulse_Attack_Interval";

  /** The interval's StartCounterAt. */
  private static final int START_COUNTER = 2500;

  @Test
  @DisplayName(
      "the pulse interval loses 50 a step with no buff and 65 a step under Rage, the rate read on"
          + " each step")
  void thePulseIntervalFollowsTheHitSpeed() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity giant =
        battle.deploy(0, records.unit("ElectroGiant_EV1"), LEVEL, 0, 3500, 10000, "giant");
    // Kept in place, so nothing comes in reach and nothing else acts on it.
    int limit = 100;
    while (interval(giant) == null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      battle.getBattle().step();
      giant.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }
    assertThat(counter(giant)).as("the counter after its first step").isLessThan(START_COUNTER);

    List<Integer> plain = deltas(battle, giant, 5);
    assertThat(plain).as("no buff: half of 100").containsOnly(50);

    giant.spawnBuff("test", "Rage", 5000, giant);
    // The step that lists the buff and the one after it are left out: only the steps that read
    // the listed buff from start to end are asserted.
    deltas(battle, giant, 2);
    assertThat(giant.getBuffs().hitSpeed(100)).isEqualTo(130);
    List<Integer> raged = deltas(battle, giant, 10);
    assertThat(raged).as("Rage: half of 130").containsOnly(65);
  }

  @Test
  @DisplayName(
      "each pulse lowers an enemy Musketeer's level by one, the second through the debuff's stacked"
          + " action, its full hit points falling with the maximum, and leaves a crown tower's level")
  void thePulseDebuffLowersTheLevelOncePerPulse() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    // The giant 5500 below side 1's right princess tower, within its pulse's reach; the Musketeer
    // 5000 to its left, out of the reach of its reflect.
    CharacterEntity giant =
        battle.deploy(0, records.unit("ElectroGiant_EV1"), LEVEL, 0, 14500, 20000, "giant");
    CharacterEntity musketeer =
        battle.deploy(0, records.unit("Musketeer"), LEVEL, 1, 9500, 20000, "musketeer");
    TowerEntity tower = null;
    for (TowerEntity princess : battle.getWorld().princessTowers(1)) {
      if (princess.getView().getX() == 14500) {
        tower = princess;
      }
    }
    assertThat(tower).isNotNull();
    int towerLevel = tower.getPackedLevel();
    int towerMaximum = tower.getHitPoints().getMaximum();
    int level = musketeer.getPackedLevel();

    // The first pulse lists the debuff: its start action lowers the level by one.
    stepUntilLevel(battle, giant, musketeer, level - 1, 200);
    int lowered = musketeer.getHitPoints().getMaximum();
    assertThat(lowered).isLessThan(maximumAt(records, level));
    assertThat(musketeer.getHitPoints().getHitPoints())
        .as("full hit points fall to the new maximum")
        .isEqualTo(lowered);

    // The next pulse refreshes it: its stacked action lowers the level once more.
    stepUntilLevel(battle, giant, musketeer, level - 2, 200);
    assertThat(musketeer.getHitPoints().getMaximum()).isLessThan(lowered);
    assertThat(musketeer.getHitPoints().getHitPoints())
        .isEqualTo(musketeer.getHitPoints().getMaximum());

    assertThat(tower.getPackedLevel()).as("a crown tower is left alone").isEqualTo(towerLevel);
    assertThat(tower.getHitPoints().getMaximum()).isEqualTo(towerMaximum);
  }

  /**
   * Steps the battle, both units kept in place, until the Musketeer's level is the one asked for;
   * fails if it is not reached within the limit or passes it.
   */
  private static void stepUntilLevel(
      Standard1v1Battle battle,
      CharacterEntity giant,
      CharacterEntity musketeer,
      int level,
      int limit) {
    for (int i = 0; i < limit && musketeer.getPackedLevel() != level; i++) {
      assertThat(musketeer.getPackedLevel()).isGreaterThan(level);
      battle.getBattle().step();
      giant.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }
    assertThat(musketeer.getPackedLevel()).isEqualTo(level);
  }

  /** The Musketeer's maximum hit points at a packed level. */
  private static int maximumAt(BattleRecords records, int packed) {
    UnitData row = records.unit("Musketeer");
    return LevelScaling.hitpoints(
        ScalingGlobals.standard(), row.hitpoints(), packed, row.rarity(), false, false);
  }

  /** The counter's change on each of the next steps, the counter falling. */
  private static List<Integer> deltas(Standard1v1Battle battle, CharacterEntity giant, int steps) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < steps; i++) {
      long before = counter(giant);
      battle.getBattle().step();
      out.add((int) (before - counter(giant)));
    }
    return out;
  }

  private static long counter(CharacterEntity giant) {
    return ((CountingRun) interval(giant)).counter();
  }

  /** The giant's interval run, or null when it is not listed. */
  private static ActionInstance interval(CharacterEntity giant) {
    return giant.actionHolder().running().stream()
        .filter(run -> run.getAction().name().equals(INTERVAL))
        .findFirst()
        .orElse(null);
  }
}
