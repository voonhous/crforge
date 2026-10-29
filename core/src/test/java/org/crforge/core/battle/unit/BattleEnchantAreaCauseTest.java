/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, started by an area effect's hit: the area effect is its
 * cause. The game takes an area effect's parent as the next link of the walk back, and an area
 * effect it ends on as the giver, keeping its id and rarity; the battle core models neither, and
 * refuses each by name. No shipped row starts the buff from an area effect.
 *
 * <p>The scene: a Zap, given a hand-written hit action, lands on a Knight in front of a princess
 * tower; the hit action starts an enchanting buff on the Knight.
 */
class BattleEnchantAreaCauseTest {

  private static final int LEVEL = 1;

  /** The hand-written enchanting buff the Zap's hit starts. */
  private static final String ENCHANT = "TestEnchant";

  /** Long enough for the Zap to land and hit. */
  private static final int TICKS = 300;

  @Test
  @DisplayName(
      "an enchanting buff whose walk back meets an area effect is refused by name: the area"
          + " effect's parent, the game's next link, is not modelled")
  void aWalkThroughAnAreaEffectIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = scene(tables(folder, 2));

    assertThatThrownBy(() -> stepTo(match, TICKS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            ENCHANT + " walks back from an area effect to its parent, which is not modelled");
  }

  @Test
  @DisplayName(
      "an enchanting buff whose walk ends on an area effect is refused by name: an area effect as"
          + " the giver, its id and rarity kept, is not modelled")
  void anAreaEffectGiverIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = scene(tables(folder, 1));

    assertThatThrownBy(() -> stepTo(match, TICKS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            ENCHANT + " takes an area effect as its giver, whose id and rarity are not modelled");
  }

  /** The Knight 3000 in front of side 1's right princess tower, the Zap 2000 in front of it. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    WorldEntity tower = rightPrincessTower(match, 1);
    int x = tower.getView().getX();
    int y = tower.getView().getY();
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, x, y - 3000, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, x, y - 2000, "zap");
    return match;
  }

  /** Steps a battle until its counter shows the given tick. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The princess tower of a side on the right lane, right of the arena's middle. */
  private static WorldEntity rightPrincessTower(Standard1v1Battle match, int side) {
    for (BattleEntity listed : match.getBattle().getHolder().entities()) {
      if (listed instanceof WorldEntity entity
          && entity.getData().name().equals("PrincessTower")
          && entity.side() == side
          && entity.getView().getX() > 9000) {
        return entity;
      }
    }
    throw new IllegalStateException("no right princess tower of side " + side);
  }

  /**
   * The tables with the enchanting buff written, its giver the given links back from its cause plus
   * one, and the Zap starting it on each object it hits.
   */
  private static GameTables tables(Path folder, int instigatorDepth) throws IOException {
    GameData.altered(
        folder,
        "area_effect_objects",
        rows -> GameData.columns(rows, "Zap").put("OnHitAction", ENCHANT));
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(ENCHANT);
          row.put("class", "LogicActionGiantBufferBuffData");
          row.put("ClassType", "ActionGiantBufferBuff");
          row.putObject("fields")
              .put("ClassType", "ActionGiantBufferBuff")
              .put("AttackAmount", 3)
              .put("AddedDamage", 50)
              .put("AddedCrownTowerDamage", 70)
              .put("FinishIfInstigatorDies", 500)
              .put("InstigatorDepth", instigatorDepth);
        });
    return GameTables.load(folder);
  }
}
