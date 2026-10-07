package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a projectile's target buff, applied after its damage, reaches what the projectile hit within
 * the tick of the impact, and so whether the victim's combat gate of that tick sees a stun it
 * carries. The game of data version 16.402.18 applies the buff at the holder's damage drain, right
 * after the hit's damage, to each victim the drain lets the hit through to: after every post-hook
 * of the tick, so the victim's own gate first reads the stun on the next tick, and the victim keeps
 * its targeting component, and its targeting visit, for one more tick. The game of 14.593.1 applies
 * it at the impact, in the projectile's post-hook, which runs ahead of every character's: the
 * victim's gate of the same tick switches the component off.
 *
 * <p>The buff's own count is the same on both: applied after the tick's buff pass, it is whole at
 * the end of the impact's tick. Two scenes, each run on the configured tables and on those tables
 * relabelled as data version 16.402.18, which differ only in the version's rule: the top side's
 * Electro Dragon hits the bottom side's left princess tower with one bolt (a projectile without a
 * radius, ZapFreeze for 500 ms), and the top side's Ice Spirit lands on a Giant of the bottom side
 * (a projectile with a radius, Freeze for 1100 ms).
 */
class BattleProjectileBuffDrainTest {

  /** The level of the towers and of the units. */
  private static final int LEVEL = 1;

  /** Long enough for every deploy and the first hit. */
  private static final int TICKS = 300;

  @TempDir Path folder;

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
    return record(match, tower, "ZapFreeze", TowerEntity.TARGETING_SLOT);
  }

  /** The top side's Ice Spirit running onto a Giant of the bottom side; the towers do not fight. */
  private static Record spiritOnGiant(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity giant = match.deploy(0, GameData.unit("Giant"), LEVEL, 0, 9000, 11000, "Giant");
    match.deploy(0, GameData.unit("IceSpirits"), LEVEL, 1, 9000, 15000, "Spirit");
    return record(match, giant, "Freeze", CharacterEntity.TARGETING_SLOT);
  }

  @Test
  @DisplayName(
      "on data version 16.402.18 a bolt's ZapFreeze is applied at the damage drain: the tower"
          + " keeps its targeting through the bolt's tick and loses it on the next")
  void aBoltsStunIsAppliedAtTheDrain() throws IOException {
    Record record = dragonOnTower(GameData.relabelled(folder, GameVersions.DATA_16_402_18));
    assertThat(record.left()).containsExactly(500, 450);
    assertThat(record.targetingOn()).containsExactly(true, false);
  }

  @Test
  @DisplayName(
      "on data version 14.593.1 the same ZapFreeze is applied at the impact: the tower's gate"
          + " of the bolt's tick switches its targeting off")
  void aBoltsStunIsAppliedAtTheImpactOnTheOlderVersion() {
    assertThat(GameData.tables().version()).isEqualTo(GameVersions.DATA_14_593_1);
    Record record = dragonOnTower(GameData.tables());
    assertThat(record.left()).containsExactly(500, 450);
    assertThat(record.targetingOn()).containsExactly(false, false);
  }

  @Test
  @DisplayName(
      "on data version 16.402.18 an Ice Spirit's Freeze reaches the Giant its area damaged at"
          + " the damage drain: the Giant keeps its targeting through the impact's tick")
  void anAreaStunIsAppliedAtTheDrain() throws IOException {
    Record record = spiritOnGiant(GameData.relabelled(folder, GameVersions.DATA_16_402_18));
    assertThat(record.left()).containsExactly(1100, 1050);
    assertThat(record.targetingOn()).containsExactly(true, false);
  }

  @Test
  @DisplayName(
      "on data version 14.593.1 the same Freeze is applied at the impact: the Giant's gate of"
          + " the impact's tick switches its targeting off")
  void anAreaStunIsAppliedAtTheImpactOnTheOlderVersion() {
    Record record = spiritOnGiant(GameData.tables());
    assertThat(record.left()).containsExactly(1100, 1050);
    assertThat(record.targetingOn()).containsExactly(false, false);
  }
}
