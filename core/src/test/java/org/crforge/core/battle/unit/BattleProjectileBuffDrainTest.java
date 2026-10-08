package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a projectile's target buff, applied after its damage, reaches what the projectile hit within
 * the tick of the impact, and so whether the victim's combat gate of that tick sees a stun it
 * carries. The game applies the buff at the holder's damage drain, right after the hit's damage, to
 * each victim the drain lets the hit through to: after every post-hook of the tick, so the victim's
 * own gate first reads the stun on the next tick, and the victim keeps its targeting component, and
 * its targeting visit, for one more tick.
 *
 * <p>Applied after the tick's buff pass, the buff is whole at the end of the impact's tick. Two
 * scenes: the top side's Electro Dragon hits the bottom side's left princess tower with one bolt (a
 * projectile without a radius, its target buff for its BuffTime), and the top side's Ice Spirit
 * lands on a Giant of the bottom side (a projectile with a radius, likewise).
 */
class BattleProjectileBuffDrainTest {

  /** The level of the towers and of the units. */
  private static final int LEVEL = 1;

  /** Long enough for every deploy and the first hit. */
  private static final int TICKS = 300;

  /** The projectile a unit's row fires. */
  private static GameRow projectileOf(String unit) {
    return row("projectiles", text(unitRow(unit), "Projectile"));
  }

  /** Side 0's left princess tower. */
  private static TowerEntity leftTower(Standard1v1Battle match) {
    return match.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(t -> t.side() == 0 && !t.getData().king() && t.x() < 9000)
        .findFirst()
        .orElseThrow();
  }

  /** The time left of an entity's instance of a buff, or -1 when it carries none. */
  private static int buffLeft(WorldEntity entity, String buff) {
    for (BuffInstance instance : entity.getBuffs().items()) {
      if (instance.getBuff().name().equals(buff)) {
        return instance.getRemaining();
      }
    }
    return -1;
  }

  /**
   * What a scene records at the end of the first tick on which the victim carries the stun and of
   * the tick after it: the stun's time left, and whether the victim's targeting component is on.
   */
  private record Record(List<Integer> left, List<Boolean> targetingOn) {}

  /**
   * Steps the battle until the victim carries the stun, and records that tick's end and the next.
   */
  private static Record record(
      Standard1v1Battle match, WorldEntity victim, String buff, int targetingSlot) {
    List<Integer> left = new ArrayList<>();
    List<Boolean> on = new ArrayList<>();
    for (int step = 0; step < TICKS && left.size() < 2; step++) {
      match.getBattle().step();
      int now = buffLeft(victim, buff);
      if (now >= 0 || !left.isEmpty()) {
        left.add(now);
        on.add(victim.isActive(targetingSlot));
      }
    }
    assertThat(left).as("the victim was stunned").hasSize(2);
    return new Record(left, on);
  }

  /** The top side's Electro Dragon in reach of the bottom side's left princess tower. */
  private static Record dragonOnTower(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    TowerEntity tower = leftTower(match);
    match.deploy(0, GameData.unit("ElectroDragon"), LEVEL, 1, 3500, 10500, "Dragon");
    return record(
        match,
        tower,
        text(projectileOf("ElectroDragon"), "TargetBuff"),
        TowerEntity.TARGETING_SLOT);
  }

  /** The top side's Ice Spirit running onto a Giant of the bottom side; the towers do not fight. */
  private static Record spiritOnGiant(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity giant = match.deploy(0, GameData.unit("Giant"), LEVEL, 0, 9000, 11000, "Giant");
    match.deploy(0, GameData.unit("IceSpirits"), LEVEL, 1, 9000, 15000, "Spirit");
    return record(
        match,
        giant,
        text(projectileOf("IceSpirits"), "TargetBuff"),
        CharacterEntity.TARGETING_SLOT);
  }

  @Test
  @DisplayName(
      "a bolt's ZapFreeze is applied at the damage drain: the tower"
          + " keeps its targeting through the bolt's tick and loses it on the next")
  void aBoltsStunIsAppliedAtTheDrain() {
    Record record = dragonOnTower(GameData.tables());
    // Whole at the end of the bolt's tick, one visit less at the end of the next.
    int time = number(projectileOf("ElectroDragon"), "BuffTime");
    assertThat(record.left()).containsExactly(time, time - 50);
    assertThat(record.targetingOn()).containsExactly(true, false);
  }

  @Test
  @DisplayName(
      "an Ice Spirit's Freeze reaches the Giant its area damaged at"
          + " the damage drain: the Giant keeps its targeting through the impact's tick")
  void anAreaStunIsAppliedAtTheDrain() {
    Record record = spiritOnGiant(GameData.tables());
    int time = number(projectileOf("IceSpirits"), "BuffTime");
    assertThat(record.left()).containsExactly(time, time - 50);
    assertThat(record.targetingOn()).containsExactly(true, false);
  }
}
