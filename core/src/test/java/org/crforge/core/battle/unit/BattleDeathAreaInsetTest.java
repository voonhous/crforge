/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The area effect a unit's death slot leaves where it dies is created as every game object is: its
 * point kept the creation's inset inside each edge of the arena, though the unit itself stood on
 * the edge (pushed there).
 */
class BattleDeathAreaInsetTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "a unit that dies on the arena's edges leaves its death area the creation's inset inside"
          + " them, and one inside the arena leaves it where it stood")
  void deathAreasStayInsideTheArena() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    BattleWorld world = match.getWorld();
    int width = world.getGrid().getWidth() * TileMap.CELL_UNITS;
    int height = world.getGrid().getHeight() * TileMap.CELL_UNITS;
    int inset = CardPlacement.CREATION_INSET;
    UnitData row = GameData.unit("Golemite");
    String deathArea = row.deathAreaEffect();
    assertThat(deathArea).as("the row leaves an area effect as it dies").isNotNull();
    List<AreaEffectEntity> areas = new ArrayList<>();
    world.addObserver(
        new WorldObserver() {
          @Override
          public void areaEffectCreated(
              int tick, AreaEffectEntity areaEffect, String how, String source) {
            if (how.equals("death") && areaEffect.getData().name().equals(deathArea)) {
              areas.add(areaEffect);
            }
          }
        });

    // One on the left edge, one in the far right corner (its own side's, out of every enemy
    // tower's reach), and one well inside the arena; none of them moves or targets.
    int[][] points = {{0, 14000}, {width - 1, height - 1}, {6000, 10000}};
    int[] sides = {0, 1, 0};
    List<CharacterEntity> units = new ArrayList<>();
    for (int i = 0; i < points.length; i++) {
      CharacterEntity unit =
          match.deploy(0, row, LEVEL, sides[i], points[i][0], points[i][1], "unit" + i);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      unit.setActive(CharacterEntity.TARGETING_SLOT, false);
      units.add(unit);
    }
    for (int i = 0; i < 30; i++) {
      match.getBattle().step();
    }
    for (int i = 0; i < units.size(); i++) {
      assertThat(units.get(i).getView().getX()).isEqualTo(points[i][0]);
      assertThat(units.get(i).getView().getY()).isEqualTo(points[i][1]);
      world.kill(units.get(i), null);
    }
    match.getBattle().step();
    match.getBattle().step();

    assertThat(areas).hasSize(points.length);
    assertThat(areas)
        .extracting(area -> List.of(area.getX(), area.getY()))
        .containsExactlyInAnyOrder(
            List.of(inset, 14000), List.of(width - inset, height - inset), List.of(6000, 10000));
  }
}
