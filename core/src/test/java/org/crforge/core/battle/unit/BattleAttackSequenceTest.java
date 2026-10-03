package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.SetAttackSequenceIndex;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit's attack sequence where the Archer runs do not reach it: the index an action stores, the
 * entries the loader builds, target_in_range, and the sequences that are refused.
 */
class BattleAttackSequenceTest {

  @Test
  @DisplayName(
      "the evolved Archer's sequence: two in its order, its own arrow then the double-damage one")
  void theArchersSequence() {
    UnitData archer = GameData.unit("Archer_EV1");
    assertThat(archer.attackSequence().mode()).isZero();
    assertThat(archer.attackSequence().order()).containsExactly(0, 1);
    assertThat(archer.attackSequence().entries().get(0).projectile().name())
        .isEqualTo("Archer_EV1_Arrow");
    assertThat(archer.attackSequence().entries().get(1).projectile().name())
        .isEqualTo("Archer_EV1_ArrowDoubleDamage");
    assertThat(archer.onStartingAttackAction()).isEqualTo("Archer_EV1_AttackSelect");
    UnitData knight = GameData.unit("Knight");
    assertThat(knight.attackSequence().order()).as("the loader keeps one").containsExactly(0);
  }

  @Test
  @DisplayName("an action stores the index only below the order's length; a longer one is dropped")
  void theIndexIsBounded() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity archer =
        new CharacterEntity(
            match.getWorld(), GameData.unit("Archer_EV1"), "Archer", 0, 3500, 10000, 11);
    ActionHolder holder = archer.actionHolder();

    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 1, false), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).isEqualTo(1);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 2, false), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).as("kept").isEqualTo(1);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 0, false), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).isZero();
  }

  @Test
  @DisplayName(
      "target_in_range compares the target's distance with its radius, the argument and the unit's"
          + " own radius, and answers 0 without a target")
  void targetInRange() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity tower = BattleMusketeerRunTest.towerNamed(match.getBattle(), "PrincessTower_1_1");
    CharacterEntity archer =
        new CharacterEntity(
            match.getWorld(), GameData.unit("Archer_EV1"), "Archer", 0, 3500, 20000, 11);
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(archer, match.getWorld());
    assertThat(evaluate("target_in_range(4500)", environment)).as("no target").isZero();

    archer.getTargeting().setReference(tower.getTargetView());
    int reach = tower.getView().getCollisionRadius() + archer.getView().getCollisionRadius();
    int distance = tower.getView().getY() - 20000;
    assertThat(evaluate("target_in_range(" + (distance - reach) + ")", environment)).isEqualTo(1);
    assertThat(evaluate("target_in_range(" + (distance - reach - 1) + ")", environment)).isZero();
  }

  @Test
  @DisplayName("a sequence the runs do not hold is refused as the unit is made")
  void unheldSequencesAreRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    // The Princess_crazy_1's StaticLoop entries set their hit speed multiplier, which no run holds.
    assertThatThrownBy(
            () ->
                new CharacterEntity(
                    match.getWorld(), GameData.unit("Princess_crazy_1"), "P", 0, 3500, 10000, 11))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("sets more than its damage and projectile");
  }

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }
}
