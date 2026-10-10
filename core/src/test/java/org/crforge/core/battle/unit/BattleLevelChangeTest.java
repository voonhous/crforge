/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A level-changing action on units in the battle: it changes its cause's level, or with
 * ExecuteOnParent the level of the entity whose holder runs it, and the unit's damage and hit
 * points follow the new level as the level change has them.
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

  /** A hand-written level change: the expression form, with the given ExecuteOnParent column. */
  private static final String WRITTEN_LEVEL_UP = "TestLevelUp";

  /** The steps the written level change adds. */
  private static final int WRITTEN_STEPS = 2;

  /**
   * The configured tables with {@link #WRITTEN_LEVEL_UP} written: a relative adjustment of {@link
   * #WRITTEN_STEPS} as an expression, run on the cause or, with ExecuteOnParent, on the entity
   * whose holder runs it.
   */
  private static GameTables writtenLevelUp(Path folder, boolean onParent) throws IOException {
    return GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(WRITTEN_LEVEL_UP);
          row.put("class", "LogicActionSetCharacterLevelData");
          row.put("ClassType", "ActionSetCharacterLevel");
          ObjectNode fields = row.putObject("fields");
          fields.put("ClassType", "ActionSetCharacterLevel");
          fields.put("RelativeLevelAdjustmentExpression", String.valueOf(WRITTEN_STEPS));
          fields.put("ExecuteOnParent", onParent);
        });
  }

  @Test
  @DisplayName(
      "a level change run on its parent changes the entity whose holder runs it, not its cause;"
          + " written false, the column leaves it on the cause")
  void onTheParentTheHoldersOwnerChanges(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            writtenLevelUp(Files.createDirectories(folder.resolve("parent")), true), 11, false);
    CharacterEntity owner = knight(match, 11, "Owner", 3500);
    CharacterEntity cause = knight(match, 11, "Cause", 14500);
    CharacterEntity raised = knight(match, 11 + WRITTEN_STEPS, "Raised", 9000);
    match.getBattle().step();

    owner
        .actionHolder()
        .start(
            match.getWorld().getActions().build(WRITTEN_LEVEL_UP, match.getWorld().binding(owner)),
            cause.actionHolder());
    assertThat(owner.level()).isEqualTo(11 + WRITTEN_STEPS);
    assertThat(owner.getHitPoints().getMaximum()).isEqualTo(raised.getHitPoints().getMaximum());
    assertThat(owner.getDamage()).isEqualTo(raised.getDamage());
    assertThat(cause.level()).as("the cause is left as it was").isEqualTo(11);

    Standard1v1Battle onCause =
        new Standard1v1Battle(
            writtenLevelUp(Files.createDirectories(folder.resolve("cause")), false), 11, false);
    CharacterEntity holder = knight(onCause, 11, "Owner", 3500);
    CharacterEntity instigator = knight(onCause, 11, "Cause", 14500);
    onCause.getBattle().step();
    holder
        .actionHolder()
        .start(
            onCause
                .getWorld()
                .getActions()
                .build(WRITTEN_LEVEL_UP, onCause.getWorld().binding(holder)),
            instigator.actionHolder());
    assertThat(instigator.level()).isEqualTo(11 + WRITTEN_STEPS);
    assertThat(holder.level()).isEqualTo(11);
  }

  @Test
  @DisplayName("a level change run on its parent needs no cause, and leaves a dead parent alone")
  void onTheParentWithoutACauseAndOnADeadParent(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(writtenLevelUp(folder, true), 11, false);
    CharacterEntity owner = knight(match, 11, "Owner", 3500);
    CharacterEntity dead = knight(match, 11, "Dead", 14500);
    CharacterEntity cause = knight(match, 11, "Cause", 9000);
    match.getBattle().step();

    owner
        .actionHolder()
        .start(
            match.getWorld().getActions().build(WRITTEN_LEVEL_UP, match.getWorld().binding(owner)));
    assertThat(owner.level()).isEqualTo(11 + WRITTEN_STEPS);

    dead.getHitPoints().setHitPoints(0);
    dead.actionHolder()
        .start(
            match.getWorld().getActions().build(WRITTEN_LEVEL_UP, match.getWorld().binding(dead)),
            cause.actionHolder());
    assertThat(dead.level()).isEqualTo(11);
    assertThat(cause.level()).isEqualTo(11);
  }

  /** The lifetime the decaying Knight's row is written with, in milliseconds. */
  private static final int LIFE_TIME_MS = 20000;

  @Test
  @DisplayName(
      "a unit whose hit points decay keeps decaying after a level change: the step is worked out"
          + " again from the new maximum and the lifetime, the carried hundredths are kept")
  void aDecayingUnitKeepsDecaying(@TempDir Path folder) throws IOException {
    knightWritten(folder);
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Knight").put("LifeTime", LIFE_TIME_MS));
    Standard1v1Battle match = new Standard1v1Battle(GameTables.load(folder), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    CharacterEntity twelve = knight(match, 12, "Twelve", 14500);
    for (int i = 0; i < 30; i++) {
      match.getBattle().step();
    }
    HitPoints hitPoints = knight.getHitPoints();
    int oldMaximum = hitPoints.getMaximum();
    assertThat(hitPoints.getDecayStep()).isEqualTo(oldMaximum * 100_000 / LIFE_TIME_MS / 20);
    int carry = hitPoints.getDecayCarry();
    int before = hitPoints.getHitPoints();
    assertThat(before).as("the unit has decayed").isLessThan(oldMaximum);

    knight.actionHolder().start(relative(1), knight.actionHolder());

    int maximum = twelve.getHitPoints().getMaximum();
    assertThat(knight.level()).isEqualTo(12);
    assertThat(hitPoints.getMaximum()).isEqualTo(maximum);
    assertThat(hitPoints.getDecayStep()).isEqualTo(maximum * 100_000 / LIFE_TIME_MS / 20);
    assertThat(hitPoints.getDecayCarry()).isEqualTo(carry);
    assertThat(hitPoints.getHitPoints())
        .isEqualTo(Math.max(before, maximum * (before * 100_000 / oldMaximum) / 100_000));
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
