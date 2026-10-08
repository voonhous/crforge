package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A character taking another row, in the cases golemite_convert does not reach: hit points kept
 * above a smaller maximum, a target given up by the row and the attack timing it leaves, a target
 * kept on the new row's columns, the new row as the unit's attackers read it, the swaps that are
 * refused, and the hit-point functions the swap's rows read.
 */
class BattleChangeDataTest {

  /** The Knight's hit points at the first level, written into its row. */
  private static final int KNIGHT_HIT_POINTS = 690;

  /** The Barbarian's, smaller, written into its row. */
  private static final int BARBARIAN_HIT_POINTS = 280;

  /** The Goblin Demolisher's kamikaze form's lifetime, written into its row. */
  private static final int KAMIKAZE_LIFETIME_MS = 20000;

  @TempDir static Path tablesFolder;

  /** The configured tables with the columns these tests read written. */
  private static GameTables tables;

  /** The records of {@link #tables}. */
  private static BattleRecords records;

  @BeforeAll
  static void writeTheRows() throws IOException {
    tables =
        GameData.altered(
            tablesFolder,
            "characters",
            rows -> {
              GameData.columns(rows, "Knight")
                  .put("Hitpoints", KNIGHT_HIT_POINTS)
                  .put("Rarity", "Common");
              GameData.columns(rows, "Barbarian")
                  .put("Hitpoints", BARBARIAN_HIT_POINTS)
                  .put("Rarity", "Common");
              GameData.columns(rows, "GoblinDemolisher_kamikaze_form")
                  .put("LifeTime", KAMIKAZE_LIFETIME_MS);
            });
    records = new BattleRecords(tables);
  }

  /** A walking bottom-side Knight at level 11 that holds a target, the towers standing still. */
  private static final class Scene {
    final Standard1v1Battle match =
        new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    final Battle battle = match.getBattle();
    final CharacterEntity knight;

    Scene(String row) {
      knight = match.deploy(0, records.unit(row), 11, 0, 3500, 10000, "Unit");
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
    // 690 at level 11 of a Common row: 690 * 256 / 100.
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(1766);

    knight.changeData("Barbarian", false);

    assertThat(knight.getHitPoints().getHitPoints()).as("kept, not capped").isEqualTo(1766);
    assertThat(knight.getHitPoints().getMaximum())
        .isEqualTo(
            new CharacterEntity(
                    scene.match.getWorld(), records.unit("Barbarian"), "B", 0, 3500, 10000, 11)
                .getHitPoints()
                .getMaximum());
    assertThat(knight.getDamage())
        .isEqualTo(
            new CharacterEntity(
                    scene.match.getWorld(), records.unit("Barbarian"), "B", 0, 3500, 10000, 11)
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
        .isEqualTo(records.unit("Archer").range());

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
          + " level and its hit points drain over the new row's lifetime; a swap back away from"
          + " the lifetime ends the drain")
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
        .isEqualTo(
            HitPoints.decayStep(demolisher.getHitPoints().getMaximum(), KAMIKAZE_LIFETIME_MS));
    int drained = demolisher.getHitPoints().getHitPoints();
    demolisher.changeData("GoblinDemolisher", false);
    assertThat(demolisher.getHitPoints().getDecayStep()).isZero();
    assertThat(demolisher.getHitPoints().getHitPoints()).isEqualTo(drained);
  }

  @Test
  @DisplayName(
      "a tower reads the row its target has now: through a lethal damage on its way it keeps a"
          + " unit that took a row with a lifetime as a target it may take, so when the unit"
          + " leaves its attack runs on for the attack finish time; on the old row it keeps the"
          + " unit only for the hit it started and stops as the unit leaves")
  void attackersReadTheNewRow() {
    assertThat(towerStatesAfterTheDemolisherLeaves(true))
        .as("kept on the kamikaze form's lifetime: the target-lost countdown runs")
        .containsExactly(
            GridEntityState.ATTACKING,
            GridEntityState.ATTACKING,
            GridEntityState.ATTACKING,
            GridEntityState.ATTACKING,
            GridEntityState.ATTACKING,
            GridEntityState.STANDING);
    assertThat(towerStatesAfterTheDemolisherLeaves(false))
        .as("kept for the damage on its way only: no countdown")
        .containsOnly(GridEntityState.STANDING);
  }

  /**
   * A bottom-side princess tower that has hit a top-side Goblin Demolisher, the demolisher swapped
   * to its kamikaze form or not, then a lethal damage put on its way to the demolisher for a step,
   * and the demolisher killed: the tower's state on each of the six steps from the one the
   * demolisher leaves in.
   */
  private static List<Integer> towerStatesAfterTheDemolisherLeaves(boolean swap) {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    Battle battle = match.getBattle();
    CharacterEntity demolisher =
        match.deploy(0, records.unit("GoblinDemolisher"), 11, 1, 3500, 15500, "Demolisher");
    TowerEntity tower = null;
    for (int tick = 0; tick < 400 && tower == null; tick++) {
      battle.step();
      // A deploying unit is out of reach of damage, which the rule asks first.
      if (demolisher.getView().getState() == GridEntityState.DEPLOYING) {
        continue;
      }
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof TowerEntity candidate
            && candidate.side() == 0
            && candidate.getTargeting().getReference() == demolisher.getTargetView()) {
          tower = candidate;
        }
      }
    }
    assertThat(tower).as("a tower attacks the demolisher").isNotNull();
    assertThat(tower.getTargeting().isHitStarted()).as("it has hit").isTrue();
    if (swap) {
      demolisher.changeData("GoblinDemolisher_kamikaze_form", false);
    }
    demolisher.addPendingDamage(demolisher.getHitPoints().getHitPoints() + 100, 200);
    battle.step();
    assertThat(tower.getTargeting().getReference())
        .as("the tower keeps the demolisher either way")
        .isSameAs(demolisher.getTargetView());
    assertThat(tower.getTargeting().isKeptByPendingDamageCheck())
        .as("kept for the hit it started only on the old row")
        .isEqualTo(!swap);

    demolisher.getHitPoints().setHitPoints(0);
    List<Integer> states = new ArrayList<>();
    for (int step = 0; step < 6; step++) {
      battle.step();
      assertThat(battle.getHolder().entities()).doesNotContain(demolisher);
      states.add(tower.getView().getState());
    }
    return states;
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
    assertThatThrownBy(() -> new Scene("Knight").knight.changeData("KnightHero", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("another ability");
    assertThatThrownBy(() -> new Scene("Knight").knight.changeData("Prince", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a charge or a river jump");
    assertThatThrownBy(() -> new Scene("Knight").knight.changeData("Assassin", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a dash");
    Scene towers = new Scene("Knight");
    TowerEntity king = BattleTowers.towerNamed(towers.battle, "KingTower_0_0");
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

    // 1000 of 1766.
    assertThat(evaluate("(hp * 100) / max_hp", environment)).isEqualTo(56);
    assertThatThrownBy(() -> evaluate("max_hp(10)", environment))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("max_hp with a level");
  }

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }
}
