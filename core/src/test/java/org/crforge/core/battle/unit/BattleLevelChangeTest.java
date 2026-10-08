package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.SetCharacterLevel;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A level-changing action on units in the battle: it changes its cause's level, and the unit's
 * damage and hit points follow the new level as the level change has them.
 */
class BattleLevelChangeTest {

  /** A Knight of the given level, placed on tick 0 for the bottom side. */
  private static CharacterEntity knight(Standard1v1Battle match, int level, String name, int x) {
    return match.deploy(0, match.getWorld().getRecords().unit("Knight"), level, 0, x, 10000, name);
  }

  private static SetCharacterLevel relative(int steps) {
    return new SetCharacterLevel(ActionRow.named("level"), steps, 1);
  }

  /**
   * The configured tables with the Knight's hit points (690) and damage (79) written, a Common row:
   * at level 11 they are 1766 and 202, at level 12 1938 and 221.
   */
  private static GameTables knightWritten(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("Hitpoints", 690)
                .put("Damage", 79)
                .put("Rarity", "Common"));
  }

  @Test
  @DisplayName("a rise changes the cause, whose hit points keep their share and whose hits follow")
  void aRiseChangesTheCause(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(knightWritten(folder), 11, false);
    CharacterEntity owner = knight(match, 11, "Owner", 3500);
    CharacterEntity cause = knight(match, 11, "Cause", 14500);
    CharacterEntity twelve = knight(match, 12, "Twelve", 9000);
    match.getBattle().step();
    cause.getHitPoints().setHitPoints(1003);

    new ActionHolder(owner).start(relative(1), cause.actionHolder());

    assertThat(cause.level()).isEqualTo(12);
    assertThat(cause.getDamage()).isEqualTo(twelve.getDamage()).isEqualTo(221);
    assertThat(cause.getHitPoints().getMaximum()).isEqualTo(1938);
    assertThat(cause.getHitPoints().teamPool(0)).isEqualTo(1938);
    assertThat(cause.getHitPoints().teamPool(1)).isEqualTo(1938);
    assertThat(cause.getHitPoints().getHitPoints())
        .as("1003 of 1766 is 56795 hundred-thousandths, 1100 of 1938")
        .isEqualTo(1100);
    assertThat(owner.level()).as("the owner is left as it was").isEqualTo(11);
    assertThat(owner.getHitPoints().getMaximum()).isEqualTo(1766);
  }

  @Test
  @DisplayName("a fall takes the hit points down to their share of the new maximum, never below 1")
  void aFallLowersTheHitPointsToTheirShare() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    CharacterEntity ten = knight(match, 10, "Ten", 14500);
    CharacterEntity low = knight(match, 11, "Low", 9000);
    match.getBattle().step();
    int tenMaximum = ten.getHitPoints().getMaximum();

    knight.actionHolder().start(relative(-1), knight.actionHolder());

    assertThat(knight.level()).isEqualTo(10);
    assertThat(knight.getDamage()).isEqualTo(ten.getDamage());
    assertThat(knight.getHitPoints().getMaximum()).isEqualTo(tenMaximum);
    assertThat(knight.getHitPoints().getHitPoints())
        .as("full hit points stay full: 1766 of 1766 is the whole new maximum")
        .isEqualTo(tenMaximum);

    // 1 of 1766 is 56 hundred-thousandths, which is 0 of the new maximum: held at 1.
    low.getHitPoints().setHitPoints(1);
    low.actionHolder().start(relative(-1), low.actionHolder());
    assertThat(low.level()).isEqualTo(10);
    assertThat(low.getHitPoints().getHitPoints()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "without a relative adjustment the absolute level is set, and the same level is kept")
  void theAbsoluteLevel() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    match.getBattle().step();

    knight
        .actionHolder()
        .start(new SetCharacterLevel(ActionRow.named("level"), 0, 5), knight.actionHolder());
    assertThat(knight.level()).isEqualTo(5);

    int hitPoints = knight.getHitPoints().getHitPoints();
    knight
        .actionHolder()
        .start(new SetCharacterLevel(ActionRow.named("level"), 0, 5), knight.actionHolder());
    assertThat(knight.level()).isEqualTo(5);
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(hitPoints);
  }

  /** The configured tables with the Royal Chef's level-up an expression of the given text. */
  private static GameTables asExpression(Path folder, String expression) throws IOException {
    return GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode fields = (ObjectNode) rows.get(CHEF_LEVEL_UP).get("fields");
          fields.remove("RelativeLevelAdjustment");
          fields.put("RelativeLevelAdjustmentExpression", expression);
        });
  }

  private static final String CHEF_LEVEL_UP = "ChefTower_increase_level_action";

  @Test
  @DisplayName(
      "a level change written as an expression moves its cause by the expression's value, held"
          + " between the first level and 99 steps above it")
  void anExpressionMovesTheLevel(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            asExpression(Files.createDirectories(folder.resolve("up")), "1"), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    CharacterEntity twelve = knight(match, 12, "Twelve", 14500);
    match.getBattle().step();
    knight
        .actionHolder()
        .start(
            match.getWorld().getActions().build(CHEF_LEVEL_UP, match.getWorld().binding(knight)),
            knight.actionHolder());
    assertThat(knight.level()).isEqualTo(12);
    assertThat(knight.getHitPoints().getMaximum()).isEqualTo(twelve.getHitPoints().getMaximum());

    Standard1v1Battle low =
        new Standard1v1Battle(
            asExpression(Files.createDirectories(folder.resolve("down")), "-20"), 11, false);
    CharacterEntity one = knight(low, 1, "One", 3500);
    low.getBattle().step();
    one.actionHolder()
        .start(
            low.getWorld().getActions().build(CHEF_LEVEL_UP, low.getWorld().binding(one)),
            one.actionHolder());
    assertThat(one.level()).as("held at the rarity's first level").isEqualTo(1);
  }

  @Test
  @DisplayName("a level change written both as an expression and as a number is refused")
  void bothFormsAreRefused(@TempDir Path folder) throws IOException {
    GameTables both =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get(CHEF_LEVEL_UP).get("fields"))
                    .put("RelativeLevelAdjustment", 1));
    Standard1v1Battle match = new Standard1v1Battle(both, 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    assertThatThrownBy(
            () ->
                match
                    .getWorld()
                    .getActions()
                    .build(CHEF_LEVEL_UP, match.getWorld().binding(knight)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("as an expression and as a number");
  }

  @Test
  @DisplayName("a dead cause, or none, is left alone, and a tower's level change is refused")
  void whatIsLeftAloneOrRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    CharacterEntity dead = knight(match, 11, "Dead", 14500);
    match.getBattle().step();
    dead.getHitPoints().setHitPoints(0);

    knight.actionHolder().start(relative(1), dead.actionHolder());
    knight.actionHolder().start(relative(1));
    assertThat(dead.level()).isEqualTo(11);
    assertThat(knight.level()).isEqualTo(11);

    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), "PrincessTower_0_1");
    assertThatThrownBy(() -> tower.actionHolder().start(relative(1), tower.actionHolder()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a tower");
  }
}
