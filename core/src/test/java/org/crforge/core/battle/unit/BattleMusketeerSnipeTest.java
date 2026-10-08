package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.column;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.numbers;
import static org.crforge.core.battle.Shipped.unitRow;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.MusketeerSnipe;
import org.crforge.core.battle.action.SetAttackSequenceIndex;
import org.crforge.core.battle.data.GameRow;
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

  /** The snipe run's action row, which the Musketeer's starting group leaves. */
  private static final String SNIPE = "Musketeer_EV1_snipe_targeting";

  /** The snipe's minimum range: an enemy closer than this is no snipe target. */
  private static final int MIN_RANGE = number(SNIPE, "SnipeMinRange");

  /** The snipe's maximum range: the look's box is half as long. */
  private static final int MAX_RANGE = number(SNIPE, "SnipeMaxRange");

  @Test
  @DisplayName("the snipe entry's CustomRange is the attack range while the index selects it")
  void theSnipeEntrySetsTheRange() {
    GameRow shipped = unitRow("Musketeer_EV1");
    int snipeRange = column(shipped, "AttackSequenceList").get(1).path("CustomRange").asInt();
    assertThat(numbers(shipped, "AttackSequence")).containsExactly(0, 1);
    UnitData row = GameData.unit("Musketeer_EV1");
    assertThat(row.attackSequence().order()).containsExactly(0, 1);
    assertThat(row.attackSequence().entries().get(1).customRange()).isEqualTo(snipeRange);
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
        .isEqualTo(snipeRange + radius);
  }

  @Test
  @DisplayName(
      "the snipe run starts with the placement, its group's delay of 0, with the row's rounds and keeps"
          + " the index at 0 while the only enemy stands inside its minimum range")
  void theSnipeRunKeepsTheNormalEntry() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer_EV1"), LEVEL, 0, 3500, 10000);
    // Half the minimum range ahead in the same lane: inside SnipeMinRange plus the Musketeer's
    // radius.
    match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 10000 + MIN_RANGE / 2);
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
    assertThat(run.getRounds()).as("the row's AmmoCount").isEqualTo(number(SNIPE, "AmmoCount"));
    assertThat(run.isFinished()).isFalse();
  }

  @Test
  @DisplayName("an enemy in the lane beyond the minimum range is a snipe target, which is refused")
  void aSnipeTargetIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    match.deploy(0, GameData.unit("Musketeer_EV1"), LEVEL, 0, 3500, 8000);
    // Ahead in the same lane, midway between SnipeMinRange and the look's half of SnipeMaxRange.
    match.deploy(
        0, GameData.unit("Knight"), LEVEL, 1, 3500, 8000 + (MIN_RANGE + MAX_RANGE / 2) / 2);
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
