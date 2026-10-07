package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.MusketeerSnipe;
import org.crforge.core.battle.action.SetAttackSequenceIndex;
import org.crforge.core.pathfinding.target.AttackRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Musketeer: an attack sequence of a normal and a snipe entry, the second with a range
 * of its own, and the snipe run its starting action leaves, which keeps the index at the normal
 * entry while no enemy stands in its lane beyond its minimum range.
 */
class BattleMusketeerSnipeTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName("the snipe entry's CustomRange is the attack range while the index selects it")
  void theSnipeEntrySetsTheRange() {
    UnitData row = GameData.unit("Musketeer_EV1");
    assertThat(row.attackSequence().order()).containsExactly(0, 1);
    assertThat(row.attackSequence().entries().get(1).customRange()).isEqualTo(30000);
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity musketeer =
        new CharacterEntity(match.getWorld(), row, "Musketeer_EV1", 0, 3500, 10000, LEVEL);
    ActionHolder holder = musketeer.actionHolder();
    int radius = musketeer.getView().getCollisionRadius();

    assertThat(AttackRange.attackRange(musketeer.getTargeting()))
        .as("the normal entry keeps the row's Range")
        .isEqualTo(row.range() + radius);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 1, true, false), holder);
    assertThat(AttackRange.attackRange(musketeer.getTargeting()))
        .as("the snipe entry's own range, the radius still added")
        .isEqualTo(30000 + radius);
  }

  @Test
  @DisplayName(
      "the snipe run starts with the placement, its group's delay of 0, with three rounds and keeps"
          + " the index at 0 while the only enemy stands inside its minimum range")
  void theSnipeRunKeepsTheNormalEntry() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer_EV1"), LEVEL, 0, 3500, 10000);
    // 4000 ahead in the same lane: inside SnipeMinRange 6000 plus the Musketeer's radius.
    match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 14000);
    MusketeerSnipe.Run run = null;
    for (int tick = 0; tick < 60; tick++) {
      battle.step();
      if (run == null) {
        run = snipeRun(musketeer.actionHolder());
        assertThat(run).as("listed on the placement's step").isNotNull();
      }
      assertThat(musketeer.getTargeting().getAttackSequenceIndex()).as("tick %d", tick).isZero();
    }
    assertThat(run).as("the snipe run is listed").isNotNull();
    assertThat(run.getRounds()).isEqualTo(3);
    assertThat(run.isFinished()).isFalse();
  }

  @Test
  @DisplayName("an enemy in the lane beyond the minimum range is a snipe target, which is refused")
  void aSnipeTargetIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    match.deploy(0, GameData.unit("Musketeer_EV1"), LEVEL, 0, 3500, 8000);
    // 12000 ahead in the same lane: between SnipeMinRange and SnipeMaxRange.
    match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 20000);
    assertThatThrownBy(
            () -> {
              for (int tick = 0; tick < 60; tick++) {
                battle.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("snipe target");
  }

  private static MusketeerSnipe.Run snipeRun(ActionHolder holder) {
    for (ActionInstance instance : holder.running()) {
      if (instance instanceof MusketeerSnipe.Run snipe) {
        return snipe;
      }
    }
    return null;
  }
}
