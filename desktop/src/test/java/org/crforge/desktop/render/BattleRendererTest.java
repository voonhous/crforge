package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.graphics.Color;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.EntityView;
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
  @DisplayName("the river and bridges stay distinct while both halves share a neutral ground")
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
    assertThat(ArenaPalette.ground(MAP.bits(water, river), true)).isEqualTo(ArenaPalette.WATER);
    assertThat(ArenaPalette.ground(MAP.bits(bridge, river), true)).isEqualTo(ArenaPalette.BRIDGE);
    int middle = MAP.width() / 2;
    assertThat(ArenaPalette.ground(MAP.bits(middle, 10), false)).isEqualTo(ArenaPalette.GRASS);
    assertThat(ArenaPalette.ground(MAP.bits(middle, MAP.height() - 11), false))
        .isEqualTo(ArenaPalette.GRASS);
  }

  @Test
  @DisplayName("flipped, side 0's bodies are red and side 1's are blue")
  void flippedColours() {
    assertThat(BattleRenderer.sideColor(0, ViewOrientation.FLIPPED)).isEqualTo(ArenaPalette.RED);
    assertThat(BattleRenderer.sideColor(1, ViewOrientation.FLIPPED)).isEqualTo(ArenaPalette.BLUE);
    assertThat(BattleRenderer.sideColor(0, ViewOrientation.STANDARD)).isEqualTo(ArenaPalette.BLUE);

    BattleFrame frame = BattleAdapter.frame(BattleSession.ladder(GameTables.loadConfigured()));
    for (EntityView tower : frame.entities()) {
      if (tower.kind() != EntityView.Kind.TOWER) {
        continue;
      }
      boolean sideOne = tower.side() == 1;
      Color flipped = BattleRenderer.bodyColor(tower, ViewOrientation.FLIPPED);
      Color standing = BattleRenderer.bodyColor(tower, ViewOrientation.STANDARD);
      assertThat(flipped).isEqualTo(sideOne ? ArenaPalette.BLUE : ArenaPalette.RED);
      assertThat(standing).isEqualTo(sideOne ? ArenaPalette.RED : ArenaPalette.BLUE);
    }
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
