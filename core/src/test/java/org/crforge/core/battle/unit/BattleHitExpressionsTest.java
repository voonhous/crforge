/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The expression functions a newer client added for a character's hits and for a point beside it:
 * target_hp, is_enough_to_kill_target, damage, and direction_offset_x / direction_offset_y. Each is
 * evaluated from a Knight of a real battle that has taken a still unit as its reference.
 */
class BattleHitExpressionsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static int evaluate(String text, BattleExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(ExpressionCompiler.compile(text, environment), environment);
  }

  /** A Knight of side 0 that has taken a still Golem of side 1 as its reference. */
  private static CharacterEntity referencing(Standard1v1Battle match) {
    CharacterEntity knight =
        match.deploy(0, match.getWorld().getRecords().unit("Knight"), LEVEL, 0, 9000, 10000);
    CharacterEntity target =
        match.deploy(0, match.getWorld().getRecords().unit("Golem"), LEVEL, 1, 9000, 13000);
    target.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    target.setActive(CharacterEntity.TARGETING_SLOT, false);
    for (int i = 0; i < 100 && knight.getTargeting().getReference() == null; i++) {
      match.getBattle().step();
    }
    assertThat(knight.getTargeting().getReference()).as("the Golem referenced").isNotNull();
    return knight;
  }

  /** The entity behind a Knight's reference. */
  private static WorldEntity reference(Standard1v1Battle match, CharacterEntity knight) {
    return match.getWorld().entityOf(knight.getTargeting().getReference().getEntity());
  }

  @Test
  @DisplayName(
      "target_hp is the hit points the reference has left, whether the targeting runs or not; 0"
          + " once the reference's hit points are switched off")
  void targetHp() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = referencing(match);
    WorldEntity golem = reference(match, knight);
    golem.getHitPoints().setHitPoints(1234);
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(knight, match.getWorld());

    assertThat(evaluate("target_hp", environment)).isEqualTo(1234);
    knight.setActive(CharacterEntity.TARGETING_SLOT, false);
    assertThat(evaluate("target_hp", environment)).as("targeting off").isEqualTo(1234);
    golem.setActive(CharacterEntity.HIT_POINTS_SLOT, false);
    assertThat(evaluate("target_hp", environment)).as("hit points off").isZero();
  }

  @Test
  @DisplayName(
      "is_enough_to_kill_target is the lethal test of the pending damage: the amount reaching the"
          + " hit points left, nothing while the shield is up or the targeting is off")
  void enoughToKillTarget() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = referencing(match);
    WorldEntity golem = reference(match, knight);
    golem.getHitPoints().setHitPoints(300);
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(knight, match.getWorld());

    assertThat(evaluate("is_enough_to_kill_target(300)", environment)).isEqualTo(1);
    assertThat(evaluate("is_enough_to_kill_target(299)", environment)).isZero();
    golem.getHitPoints().setShield(1);
    assertThat(evaluate("is_enough_to_kill_target(5000)", environment)).as("shield up").isZero();
    golem.getHitPoints().setShield(0);
    knight.setActive(CharacterEntity.TARGETING_SLOT, false);
    assertThat(evaluate("is_enough_to_kill_target(5000)", environment))
        .as("targeting off")
        .isZero();
  }

  @Test
  @DisplayName(
      "damage is the character's next hit's damage: the row's own, or with two entries that leave"
          + " their Damage out still the row's own; refused on a tower")
  void damage(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = referencing(match);
    assertThat(evaluate("damage", new BattleExpressionEnvironment(knight, match.getWorld())))
        .isEqualTo(knight.getDamage())
        .isPositive();

    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "Knight");
              columns.putArray("AttackSequence").add(0).add(1);
              ArrayNode list = columns.putArray("AttackSequenceList");
              list.addObject();
              list.addObject().put("Damage", 1);
            });
    Standard1v1Battle sequenced = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity entries = referencing(sequenced);
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(entries, sequenced.getWorld());
    assertThat(evaluate("damage", environment)).as("entry 0").isEqualTo(entries.getDamage());
    entries.setAttackSequenceIndex(1, true);
    assertThat(evaluate("damage", environment))
        .as("entry 1, of Damage 1 at the Knight's level")
        .isPositive()
        .isLessThan(entries.getDamage());

    TowerEntity king = BattleTowers.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment kingSees = new BattleExpressionEnvironment(king, match.getWorld());
    assertThatThrownBy(() -> evaluate("damage", kingSees))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("not a character");
  }

  @Test
  @DisplayName(
      "direction_offset_x and direction_offset_y rescale the character's facing to the length, a"
          + " negative one behind it, each part truncated; refused on a tower")
  void directionOffset() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = referencing(match);
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(knight, match.getWorld());

    knight.getView().setDirX(0);
    knight.getView().setDirY(256);
    assertThat(evaluate("direction_offset_x(-2500)", environment)).isZero();
    assertThat(evaluate("direction_offset_y(-2500)", environment)).isEqualTo(-2500);
    // A diagonal facing: its length is the root of 181 * 181 * 2 toward zero, 255, and each part is
    // 181 * -2500 / 255 toward zero.
    knight.getView().setDirX(181);
    knight.getView().setDirY(-181);
    assertThat(evaluate("direction_offset_x(-2500)", environment)).isEqualTo(-1774);
    assertThat(evaluate("direction_offset_y(-2500)", environment)).isEqualTo(1774);
    // No facing at all leaves nothing to rescale.
    knight.getView().setDirX(0);
    knight.getView().setDirY(0);
    assertThat(evaluate("direction_offset_y(-2500)", environment)).isZero();

    TowerEntity king = BattleTowers.towerNamed(match.getBattle(), "KingTower_1_0");
    BattleExpressionEnvironment kingSees = new BattleExpressionEnvironment(king, match.getWorld());
    assertThatThrownBy(() -> evaluate("direction_offset_x(-2500)", kingSees))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("not a character");
  }
}
