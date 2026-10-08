package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.core.util.GameUnits;
import org.junit.jupiter.api.Test;

/**
 * Rendering and input sit on the tile/pixel side of the game-unit boundary. These tests check that
 * converted drawing and deploy input land on the same pixels and tiles as before the migration.
 */
class RenderUnitConversionTest {

  @Test
  void unitsToPixels_mapsOneTileToTilePixels() {
    assertThat(RenderConstants.unitsToPixels(GameUnits.UNITS_PER_TILE))
        .isEqualTo(RenderConstants.TILE_PIXELS);
    assertThat(RenderConstants.unitsToPixels(500)).isEqualTo(RenderConstants.TILE_PIXELS / 2);
    assertThat(RenderConstants.unitsToPixels(-2750))
        .isEqualTo(-2.75f * RenderConstants.TILE_PIXELS);
    // Whole arena width in pixels is unchanged
    assertThat(RenderConstants.unitsToPixels(18_000)).isEqualTo(18 * RenderConstants.TILE_PIXELS);
  }

  @Test
  void tileClick_deploysAtTileCenterInGameUnits() {
    // A deploy action for the original engine, built from hovered tile indices
    int hoverTileX = 3;
    int hoverTileY = 7;

    PlayerActionDTO action = PlayerActionDTO.playAtTiles(1, hoverTileX + 0.5f, hoverTileY + 0.5f);

    assertThat(action.getX()).isEqualTo(GameUnits.tileCenter(hoverTileX)).isEqualTo(3500);
    assertThat(action.getY()).isEqualTo(GameUnits.tileCenter(hoverTileY)).isEqualTo(7500);
    assertThat(action.getHandIndex()).isEqualTo(1);
  }
}
