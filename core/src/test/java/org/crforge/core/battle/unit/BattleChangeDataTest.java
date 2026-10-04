package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A character taking another row, in the cases golemite_convert does not reach: hit points kept
 * above a smaller maximum, a target given up by the row and the attack timing it leaves, a target
 * kept on the new row's columns, the swaps that are refused, and the hit-point functions the swap's
 * rows read.
 */
class BattleChangeDataTest {

  /** A walking bottom-side Knight at level 11 that holds a target, the towers standing still. */
  private static final class Scene {
    final Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    final Battle battle = match.getBattle();
    final CharacterEntity knight;

    Scene(String row) {
      knight = match.deploy(0, GameData.unit(row), 11, 0, 3500, 10000, "Unit");
      for (int tick = 0; tick < 30; tick++) {
        battle.step();
      }
    }
  }

  @Test
  @DisplayName(
      "a smaller row keeps the hit points above its maximum; the maximum and the damage are the"
          + " new row's at the same level")
  void theHitPointsAreKept() {
    Scene scene = new Scene("Knight");
    CharacterEntity knight = scene.knight;
    int level = knight.getPackedLevel();
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(1766);

    knight.changeData("Barbarian", false);

    assertThat(knight.getHitPoints().getHitPoints()).as("kept, not capped").isEqualTo(1766);
    assertThat(knight.getHitPoints().getMaximum())
        .isEqualTo(
            new CharacterEntity(
                    scene.match.getWorld(), GameData.unit("Barbarian"), "B", 0, 3500, 10000, 11)
                .getHitPoints()
                .getMaximum());
    assertThat(knight.getDamage())
        .isEqualTo(
            new CharacterEntity(
                    scene.match.getWorld(), GameData.unit("Barbarian"), "B", 0, 3500, 10000, 11)
                .getDamage());
    assertThat(knight.getPackedLevel()).as("not re-based").isEqualTo(level);
    assertThat(knight.getData().name()).isEqualTo("Barbarian");
  }

  @Test
  @DisplayName(
      "a kept target is stored again on the new row's columns; one the row resets is given up,"
          + " the attack timing left as it was")
  void theTarget() {
    Scene kept = new Scene("Knight");
    TargetView target = kept.knight.getTargeting().getReference();
    assertThat(target).as("it walks at a tower").isNotNull();
    kept.knight.getTargeting().setAttackTimerMs(300);
    kept.knight.changeData("Archer", false);
    assertThat(kept.knight.getTargeting().getReference()).isSameAs(target);
    assertThat(kept.knight.getTargeting().getAttackTimerMs())
        .as("stored again through the setter: out of range, the attack timing is cleared")
        .isZero();
    assertThat(kept.knight.getTargeting().getConfig().range())
        .as("the new row's range")
        .isEqualTo(GameData.unit("Archer").range());

    Scene reset = new Scene("Knight");
    reset.knight.getTargeting().setAttackTimerMs(300);
    reset.knight.getTargeting().setLoadTimerMs(200);
    reset.knight.changeData("Archer", true);
    assertThat(reset.knight.getTargeting().getReference()).isNull();
    assertThat(reset.knight.getTargeting().getAttackTimerMs()).isEqualTo(300);
    assertThat(reset.knight.getTargeting().getLoadTimerMs()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "a walking unit without a lifetime takes a walking row with one: it keeps its hit points and"
          + " level and its hit points drain over the new row's lifetime; a swap away from a"
          + " lifetime is refused")
  void aWalkingRowWithALifetime() {
    Scene scene = new Scene("GoblinDemolisher");
    CharacterEntity demolisher = scene.knight;
    int level = demolisher.getPackedLevel();
    int hitPoints = demolisher.getHitPoints().getHitPoints();
    assertThat(demolisher.getHitPoints().getDecayStep()).isZero();

    demolisher.changeData("GoblinDemolisher_kamikaze_form", true);

    assertThat(demolisher.getHitPoints().getHitPoints()).isEqualTo(hitPoints);
    assertThat(demolisher.getPackedLevel()).isEqualTo(level);
    assertThat(demolisher.getHitPoints().getDecayStep())
        .isEqualTo(HitPoints.decayStep(demolisher.getHitPoints().getMaximum(), 20000));
    assertThatThrownBy(() -> demolisher.changeData("GoblinDemolisher", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a lifetime");
  }

  @Test
  @DisplayName("a swap whose effect is not established is refused, naming what it asks for")
  void unestablishedSwapsAreRefused() {
    assertThatThrownBy(() -> new Scene("Knight").knight.changeData("BabyDragon", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a building or a flying row");
    assertThatThrownBy(() -> new Scene("Knight").knight.changeData("Recruit_Chess", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("movement component");
    assertThatThrownBy(() -> new Scene("SkeletonKing").knight.changeData("Knight", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("another ability");
    assertThatThrownBy(() -> new Scene("Knight").knight.changeData("Prince", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a charge or a river jump");
    assertThatThrownBy(() -> new Scene("Knight").knight.changeData("Assassin", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a dash");
    Scene towers = new Scene("Knight");
    TowerEntity king = BattleMusketeerRunTest.towerNamed(towers.battle, "KingTower_0_0");
    assertThatThrownBy(() -> king.changeData("Knight", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("cannot take another data row");
  }

  @Test
  @DisplayName("hp is the unit's hit points and max_hp its maximum; max_hp at a level is refused")
  void theHitPointFunctions() {
    Scene scene = new Scene("Knight");
    BattleWorld world = scene.match.getWorld();
    scene.knight.getHitPoints().setHitPoints(1000);
    BattleExpressionEnvironment environment = new BattleExpressionEnvironment(scene.knight, world);

    assertThat(evaluate("(hp * 100) / max_hp", environment)).isEqualTo(56);
    assertThatThrownBy(() -> evaluate("max_hp(10)", environment))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("max_hp with a level");
  }

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }
}
