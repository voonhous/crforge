package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The battle as an expression sees it from a king tower. */
class BattleExpressionEnvironmentTest {

  @Test
  @DisplayName("every one of the 47 names resolves, so every expression of the data compiles")
  void everyNameResolves() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());

    for (BattleFunctions.Entry entry : BattleFunctions.ALL) {
      assertThat(environment.resolve(entry.name())).as(entry.name()).isNotNull();
      assertThat(environment.resolve(entry.name()).id()).isEqualTo(entry.id());
    }
    assertThat(environment.resolve("not_a_function")).isNull();
  }

  @Test
  @DisplayName("the king's condition follows its side's king and princess towers")
  void theKingsCondition() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    Battle battle = match.getBattle();
    battle.step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(battle, "KingTower_1_0");
    TowerEntity otherKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_0_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());
    Expression condition =
        ExpressionCompiler.compile(
            GameData.tables()
                .action("WaitForKingTowerActivation")
                .fields()
                .get("Condition")
                .asText(),
            environment);

    assertThat(ExpressionEvaluator.evaluate(condition, environment)).isZero();

    // The other side's king losing hit points leaves this king asleep.
    match.getWorld().dealDamage(otherKing.getTargetView(), 1, 0, 1);
    assertThat(ExpressionEvaluator.evaluate(condition, environment)).isZero();

    // Its own side's princess tower destroyed: the side keeps only one.
    TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, "PrincessTower_1_1");
    match.getWorld().dealDamage(princess.getTargetView(), 100000, 0, 1);
    assertThat(ExpressionEvaluator.evaluate(condition, environment))
        .as("a destroyed tower counts until the cleanup that removes it")
        .isZero();
    battle.step();
    assertThat(ExpressionEvaluator.evaluate(condition, environment)).isEqualTo(1);
  }

  @Test
  @DisplayName("the king's own hit points below the maximum wake it too")
  void theKingDamaged() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());
    int damaged = BattleFunctions.id("king_tower_damaged");

    assertThat(environment.call(damaged, new int[0])).isZero();
    match.getWorld().dealDamage(king.getTargetView(), 1, 0, 1);
    assertThat(environment.call(damaged, new int[0])).isEqualTo(1);
  }

  @Test
  @DisplayName("x and y read the context entity's position as it stands when evaluated")
  void thePosition() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());
    Expression right = ExpressionCompiler.compile("x + 1000", environment);
    Expression down = ExpressionCompiler.compile("y - 1000", environment);

    assertThat(ExpressionEvaluator.evaluate(right, environment))
        .isEqualTo(king.getView().getX() + 1000);
    assertThat(ExpressionEvaluator.evaluate(down, environment))
        .isEqualTo(king.getView().getY() - 1000);
    king.getView().setX(4000);
    king.getView().setY(7000);
    assertThat(ExpressionEvaluator.evaluate(right, environment)).isEqualTo(5000);
    assertThat(ExpressionEvaluator.evaluate(down, environment)).isEqualTo(6000);
  }

  @Test
  @DisplayName(
      "team_index is the side's low bit, and team_y_direction answers -1 for team 0 and 1"
          + " otherwise")
  void theTeam() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    BattleExpressionEnvironment bottom =
        new BattleExpressionEnvironment(
            BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_0_0"),
            match.getWorld());
    BattleExpressionEnvironment top =
        new BattleExpressionEnvironment(
            BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0"),
            match.getWorld());

    assertThat(evaluate("team_index", bottom)).isZero();
    assertThat(evaluate("team_index", top)).isEqualTo(1);
    assertThat(evaluate("team_y_direction(team_index)", bottom)).isEqualTo(-1);
    assertThat(evaluate("team_y_direction(team_index)", top)).isEqualTo(1);
    // It reads only its argument.
    assertThat(evaluate("team_y_direction(0)", top)).isEqualTo(-1);
    assertThat(evaluate("team_y_direction(7)", bottom)).isEqualTo(1);
  }

  @Test
  @DisplayName("map_width and map_height are the arena's cells times 500")
  void theMapSize() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(
            BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0"),
            match.getWorld());

    assertThat(evaluate("map_width", environment)).isEqualTo(18000);
    assertThat(evaluate("map_height", environment)).isEqualTo(32000);
  }

  @Test
  @DisplayName(
      "a character or building row's name is its global id, and has_data holds on that row"
          + " alone")
  void dataRows() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    BattleWorld world = match.getWorld();
    CharacterEntity miniPekka =
        new CharacterEntity(world, GameData.unit("MiniPekka"), "MiniPekka", 0, 3500, 10000, 11);
    CharacterEntity superMiniPekka =
        new CharacterEntity(
            world, GameData.unit("SuperMiniPekka"), "SuperMiniPekka", 0, 2500, 10000, 11);
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment onMiniPekka = new BattleExpressionEnvironment(miniPekka, world);

    assertThat(evaluate("MiniPekka", onMiniPekka)).isEqualTo(34000016);
    assertThat(evaluate("KingTower", onMiniPekka)).as("a building").isEqualTo(35000000);
    assertThat(evaluate("has_data(MiniPekka)", onMiniPekka)).isEqualTo(1);
    assertThat(
            evaluate("has_data(MiniPekka)", new BattleExpressionEnvironment(superMiniPekka, world)))
        .as("the exact row, not a relative")
        .isZero();
    assertThat(evaluate("has_data(KingTower)", new BattleExpressionEnvironment(king, world)))
        .isEqualTo(1);
    assertThat(evaluate("has_data(34000016)", onMiniPekka)).as("the id as a number").isEqualTo(1);
  }

  @Test
  @DisplayName("a row whose global id is negative answers 0 by name, even on its own row")
  void aNegativeIdCannotBeNamed() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    BattleWorld world = match.getWorld();
    // A unit carrying DaggerDuchess's global id, which hashes below zero.
    int daggerDuchess = GameData.records().unitGlobalId("DaggerDuchess");
    CharacterEntity unit =
        new CharacterEntity(
            world,
            GameData.unit("Knight").toBuilder().globalId(daggerDuchess).build(),
            "Unit",
            0,
            3500,
            10000,
            11);
    BattleExpressionEnvironment environment = new BattleExpressionEnvironment(unit, world);

    assertThat(daggerDuchess).isEqualTo(-1749071821);
    assertThat(evaluate("DaggerDuchess", environment)).isZero();
    assertThat(evaluate("has_data(DaggerDuchess)", environment)).isZero();
    assertThat(evaluate("has_data(-1749071821)", environment))
        .as("the data writes it as its number")
        .isEqualTo(1);
  }

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }

  @Test
  @DisplayName("a function the battle does not answer yet fails rather than guess")
  void anUnportedFunctionFails() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(king, match.getWorld());

    assertThatThrownBy(() -> environment.call(BattleFunctions.id("should_hide"), new int[0]))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("should_hide");
  }

  @Test
  @DisplayName(
      "is_moving answers 1 for a walking character only: 0 while it deploys, under a tag that"
          + " holds it, with its movement component off, and for a tower")
  void isMovingReadsTheSpeedBudget() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000);
    int isMoving = BattleFunctions.id("is_moving");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(knight, match.getWorld());
    match.getBattle().step();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(environment.call(isMoving, new int[0])).as("deploying").isZero();
    while (knight.getView().getState() != GridEntityState.MOVING) {
      match.getBattle().step();
    }
    assertThat(environment.call(isMoving, new int[0])).as("walking").isEqualTo(1);
    knight.getView().setFlags(knight.getView().getFlags() | EntityFlags.NO_MOVE);
    assertThat(environment.call(isMoving, new int[0])).as("held by NO_MOVE").isZero();
    knight.getView().setFlags(knight.getView().getFlags() & ~EntityFlags.NO_MOVE);
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    assertThat(environment.call(isMoving, new int[0])).as("its movement off").isZero();

    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");
    assertThat(new BattleExpressionEnvironment(king, match.getWorld()).call(isMoving, new int[0]))
        .as("a tower")
        .isZero();
  }

  @Test
  @DisplayName("get_radius answers the context's row's collision radius")
  void getRadiusIsTheCollisionRadius() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000);
    CharacterEntity golem =
        match.deploy(0, GameData.unit("Golem"), Standard1v1Battle.DEFAULT_LEVEL, 0, 5500, 10000);

    assertThat(evaluate("get_radius()", new BattleExpressionEnvironment(knight, match.getWorld())))
        .isEqualTo(500);
    assertThat(evaluate("get_radius()", new BattleExpressionEnvironment(golem, match.getWorld())))
        .isEqualTo(750);
  }

  @Test
  @DisplayName("is_clone answers a character's clone byte, and is refused on a tower")
  void isCloneIsTheCloneByte() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000);
    CharacterEntity clone =
        match.deploy(0, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 5500, 10000);
    clone.markClone(null);
    match.getBattle().step();
    TowerEntity king = BattleMusketeerRunTest.towerNamed(match.getBattle(), "KingTower_1_0");

    assertThat(evaluate("is_clone()", new BattleExpressionEnvironment(knight, match.getWorld())))
        .isZero();
    assertThat(evaluate("!is_clone()", new BattleExpressionEnvironment(clone, match.getWorld())))
        .isZero();
    assertThat(evaluate("is_clone()", new BattleExpressionEnvironment(clone, match.getWorld())))
        .isEqualTo(1);
    assertThatThrownBy(
            () -> evaluate("is_clone()", new BattleExpressionEnvironment(king, match.getWorld())))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("which is not a character");
  }

  /** A Knight of side 0 that has taken a still unit of side 1 as its reference. */
  private static CharacterEntity referencing(Standard1v1Battle match, String row) {
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 9000, 10000);
    CharacterEntity target =
        match.deploy(
            0,
            match.getWorld().getRecords().unit(row),
            Standard1v1Battle.DEFAULT_LEVEL,
            1,
            9000,
            13000);
    target.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    target.setActive(CharacterEntity.TARGETING_SLOT, false);
    for (int i = 0; i < 100 && knight.getTargeting().getReference() == null; i++) {
      match.getBattle().step();
    }
    assertThat(knight.getTargeting().getReference()).as(row + " referenced").isNotNull();
    return knight;
  }

  @Test
  @DisplayName(
      "target_max_hp(10) is the reference's row's hit points at card level 11, re-based on its"
          + " rarity, whatever level it was played at; with no argument the reference's maximum")
  void targetMaxHpAtALevel() {
    String[] rows = {"Knight", "MiniPekka", "Golem", "Skeleton", "SuperMiniPekka", "MegaMonk"};
    int[] expected = {1766, 1390, 5120, 81, 1573, 3800};
    for (int i = 0; i < rows.length; i++) {
      Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 1, false);
      BattleExpressionEnvironment environment =
          new BattleExpressionEnvironment(referencing(match, rows[i]), match.getWorld());
      assertThat(evaluate("target_max_hp(10)", environment)).as(rows[i]).isEqualTo(expected[i]);
    }
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 1, false);
    CharacterEntity knight = referencing(match, "Knight");
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(knight, match.getWorld());
    WorldEntity target =
        match.getWorld().entityOf(knight.getTargeting().getReference().getEntity());
    assertThat(evaluate("target_max_hp()", environment))
        .isEqualTo(target.getHitPoints().getMaximum());
    knight.setActive(CharacterEntity.TARGETING_SLOT, false);
    assertThat(evaluate("target_max_hp(10)", environment)).as("targeting off").isZero();
  }
}
