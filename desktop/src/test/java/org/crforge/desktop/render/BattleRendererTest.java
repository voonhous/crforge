package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.battle.BattleSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the battle renderer works out without drawing: the arena's colours from the battle's tile
 * map and a spell's preview circle from its rows. The drawing itself needs a GL context, which the
 * tests do not have; it is checked by running the visualizer.
 */
class BattleRendererTest {

  private static final TileMap MAP = TileMap.standard1v1();

  @Test
  @DisplayName("the river is water, the bridges cross it, and each half takes its side's colour")
  void arenaColours() {
    int river = riverRow();
    int water = -1;
    int bridge = -1;
    for (int col = 0; col < MAP.width(); col++) {
      if ((MAP.bits(col, river) & TileMap.WATER_BIT) != 0) {
        water = col;
      } else if ((MAP.bits(col, river) & TileMap.BLOCKED_BIT) == 0) {
        bridge = col;
      }
    }
    assertThat(water).as("a water cell on the river row").isNotNegative();
    assertThat(bridge).as("a bridge cell on the river row").isNotNegative();
    assertThat(BattleRenderer.cellColor(MAP, water, river, true))
        .isEqualTo(RenderConstants.COLOR_RIVER);
    assertThat(BattleRenderer.cellColor(MAP, bridge, river, true))
        .isEqualTo(RenderConstants.COLOR_BRIDGE);

    // A cell in front of each king: the bottom half is blue's, the top half red's.
    int middle = MAP.width() / 2;
    assertThat(BattleRenderer.cellColor(MAP, middle, 10, false))
        .isEqualTo(RenderConstants.COLOR_BLUE_ZONE);
    assertThat(BattleRenderer.cellColor(MAP, middle, MAP.height() - 11, false))
        .isEqualTo(RenderConstants.COLOR_RED_ZONE);
  }

  @Test
  @DisplayName("the arena drawn from the tile map is the window's 18 by 32 tiles")
  void arenaSize() {
    assertThat(RenderConstants.unitsToPixels(MAP.widthUnits()))
        .isEqualTo(18 * RenderConstants.TILE_PIXELS);
    assertThat(RenderConstants.unitsToPixels(MAP.heightUnits()))
        .isEqualTo(32 * RenderConstants.TILE_PIXELS);
    assertThat(MAP.width() * RenderConstants.CELL_PIXELS)
        .isEqualTo(
            MAP.widthUnits() / (float) GameUnits.UNITS_PER_TILE * RenderConstants.TILE_PIXELS);
  }

  @Test
  @DisplayName("a spell's preview circle is its area effect's, its own or its projectile's radius")
  void spellRadius() {
    GameTables tables = GameTables.loadConfigured();
    BattleWorld world = BattleSession.ladder(tables).getBattle().getWorld();
    for (String spell : new String[] {"Zap", "Fireball", "Poison", "Arrows", "Rage"}) {
      float radius = BattleRenderer.spellRadius(world, world.getRecords().card(spell));
      assertThat(radius).as("%s's preview radius", spell).isPositive();
    }
    assertThat(BattleRenderer.spellRadius(world, world.getRecords().card("Poison")))
        .isEqualTo(
            world.getRecords().areaEffect(world.getRecords().card("Poison").areaEffect()).radius());
  }

  /** The first row with a water cell. */
  private static int riverRow() {
    for (int row = 0; row < MAP.height(); row++) {
      for (int col = 0; col < MAP.width(); col++) {
        if ((MAP.bits(col, row) & TileMap.WATER_BIT) != 0) {
          return row;
        }
      }
    }
    throw new IllegalStateException("no river");
  }
}
