package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.column;
import static org.crforge.core.battle.Shipped.numbers;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.SetAttackSequenceIndex;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A unit's attack sequence where the Archer runs do not reach it: the index an action stores, the
 * entries the loader builds, target_in_range, and the sequences that are refused.
 */
class BattleAttackSequenceTest {

  @Test
  @DisplayName(
      "the evolved Archer's sequence: two in its order, its own arrow then the double-damage one")
  void theArchersSequence() {
    // The row lists no entries: its Projectile and Projectile2 are the two the order walks.
    GameRow row = unitRow("Archer_EV1");
    assertThat(numbers(row, "AttackSequence")).containsExactly(0, 1);
    UnitData archer = GameData.unit("Archer_EV1");
    assertThat(archer.attackSequence().mode()).isZero();
    assertThat(archer.attackSequence().order())
        .containsExactlyElementsOf(numbers(row, "AttackSequence"));
    assertThat(archer.attackSequence().entries().get(0).projectile().name())
        .isEqualTo(text(row, "Projectile"));
    assertThat(archer.attackSequence().entries().get(1).projectile().name())
        .isEqualTo(text(row, "Projectile2"));
    assertThat(archer.onStartingAttackAction()).isEqualTo(text(row, "OnStartingAttackAction"));
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

    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 1, false, false), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).isEqualTo(1);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 2, false, false), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).as("kept").isEqualTo(1);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 0, false, false), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).isZero();
  }

  @Test
  @DisplayName(
      "an index action that resets the hit started clears the hit-in-progress flag after its store,"
          + " stored or not, and leaves the flag of a hit without a reference")
  void anIndexActionResetsTheHitStarted() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity archer =
        new CharacterEntity(
            match.getWorld(), GameData.unit("Archer_EV1"), "Archer", 0, 3500, 10000, 11);
    ActionHolder holder = archer.actionHolder();

    archer.getTargeting().setHitInProgress(true);
    archer.getTargeting().setHitInProgressWithoutReference(true);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 1, false, false), holder);
    assertThat(archer.getTargeting().isHitInProgress()).as("kept without the reset").isTrue();

    holder.start(new SetAttackSequenceIndex(ActionRow.named("reset"), 0, false, true), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).isZero();
    assertThat(archer.getTargeting().isHitInProgress()).isFalse();
    assertThat(archer.getTargeting().isHitInProgressWithoutReference()).isTrue();

    // An index past the order is dropped, and the flag is cleared all the same.
    archer.getTargeting().setHitInProgress(true);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("reset"), 2, false, true), holder);
    assertThat(archer.getTargeting().getAttackSequenceIndex()).isZero();
    assertThat(archer.getTargeting().isHitInProgress()).isFalse();
  }

  @Test
  @DisplayName(
      "target_in_range compares the target's distance with its radius, the argument and the unit's"
          + " own radius, and answers 0 without a target")
  void targetInRange() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity tower = BattleMusketeerRunTest.towerNamed(match.getBattle(), "PrincessTower_1_1");
    // Straight below the tower, wherever the tables place it, so the distance is along y alone.
    int archerY = tower.getView().getY() - 5500;
    CharacterEntity archer =
        new CharacterEntity(
            match.getWorld(),
            GameData.unit("Archer_EV1"),
            "Archer",
            0,
            tower.getView().getX(),
            archerY,
            11);
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(archer, match.getWorld());
    assertThat(evaluate("target_in_range(4500)", environment)).as("no target").isZero();

    archer.getTargeting().setReference(tower.getTargetView());
    int reach = tower.getView().getCollisionRadius() + archer.getView().getCollisionRadius();
    int distance = tower.getView().getY() - archerY;
    assertThat(evaluate("target_in_range(" + (distance - reach) + ")", environment)).isEqualTo(1);
    assertThat(evaluate("target_in_range(" + (distance - reach - 1) + ")", environment)).isZero();
  }

  @Test
  @DisplayName(
      "an entry's variable damage time outside a timer-driven mode is refused as the unit is made")
  void unheldSequencesAreRefused(@TempDir Path folder) throws IOException {
    // The mode None walks no window, so the time would go unread.
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              ArrayNode list =
                  (ArrayNode) GameData.columns(rows, "EliteArcherHero").get("AttackSequenceList");
              ((ObjectNode) list.get(1)).put("VariableDamageTime", 1000);
            });
    Standard1v1Battle match = new Standard1v1Battle(tables);
    UnitData archer = match.getWorld().getRecords().unit("EliteArcherHero");
    assertThatThrownBy(() -> new CharacterEntity(match.getWorld(), archer, "A", 0, 3500, 10000, 11))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("variable damage time outside a timer-driven mode");
  }

  @Test
  @DisplayName("the hero Electro Wizard's entries, each with its hit speed multiplier, build")
  void entriesWithTheirPaceBuild() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity wizard =
        new CharacterEntity(
            match.getWorld(), GameData.unit("ElectroWizardHero"), "W", 0, 3500, 10000, 11);
    // Each entry's own HitSpeedMultiplier; an entry that leaves it out keeps the pace, 100.
    List<Integer> multipliers = new ArrayList<>();
    column(unitRow("ElectroWizardHero"), "AttackSequenceList")
        .forEach(entry -> multipliers.add(entry.path("HitSpeedMultiplier").asInt(100)));
    assertThat(multipliers).hasSize(3);
    assertThat(wizard.getData().attackSequence().entries())
        .extracting(AttackSequence.Entry::hitSpeedMultiplier)
        .containsExactlyElementsOf(multipliers);
  }

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }
}
