/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.util.GameUnits;
import org.junit.jupiter.api.Test;

/**
 * Rendering sits on the tile/pixel side of the game-unit boundary. These tests check that converted
 * drawing lands on the same pixels as before the migration.
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
}
