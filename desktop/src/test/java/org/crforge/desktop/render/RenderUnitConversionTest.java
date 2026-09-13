package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.card.DeployFormation;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.GameUnits;
import org.crforge.data.card.CardRegistry;
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
    // DebugGameScreen and ArenaRenderer build deploy actions from hovered tile indices this way
    int hoverTileX = 3;
    int hoverTileY = 7;

    PlayerActionDTO action = PlayerActionDTO.playAtTiles(1, hoverTileX + 0.5f, hoverTileY + 0.5f);

    assertThat(action.getX()).isEqualTo(GameUnits.tileCenter(hoverTileX)).isEqualTo(3500);
    assertThat(action.getY()).isEqualTo(GameUnits.tileCenter(hoverTileY)).isEqualTo(7500);
    assertThat(action.getHandIndex()).isEqualTo(1);
  }

  @Test
  void ghostFormation_skeletonArmyGhostsMatchSimulationRadialOffsets() {
    Card skeletonArmy = Objects.requireNonNull(CardRegistry.get("skeletonarmy"));
    int total = skeletonArmy.getTotalDeployCount();
    DeployFormation formation = DeployFormation.of(skeletonArmy);

    List<float[]> blue = GhostFormation.computePositions(skeletonArmy, total, 0, Team.BLUE, 800f);
    List<float[]> red = GhostFormation.computePositions(skeletonArmy, total, 0, Team.RED, 800f);

    assertThat(blue).hasSize(total);
    for (int i = 0; i < total; i++) {
      FormationLayout.Offset expected = formation.offsetFor(i, 500);
      assertThat(blue.get(i)[0]).as("blue x %d", i).isEqualTo((float) expected.x());
      assertThat(blue.get(i)[1]).as("blue y %d", i).isEqualTo((float) expected.y());
      // Red mirrors blue on both axes
      assertThat(red.get(i)[0]).as("red x %d", i).isEqualTo((float) -expected.x());
      assertThat(red.get(i)[1]).as("red y %d", i).isEqualTo((float) -expected.y());
    }
    // Index 1 radial offset (-563, 1266) game units, drawn in pixels
    assertThat(RenderConstants.unitsToPixels(blue.get(1)[0]))
        .isEqualTo(-563 * RenderConstants.PIXELS_PER_UNIT);
    // Visual radius (0.5 tiles) is carried in game units
    assertThat(blue.get(0)[2]).isEqualTo(500f);
  }

  @Test
  void ghostFormation_singleUnitCardUsesDefaultRadiusAtCenter() {
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"));

    List<float[]> ghosts = GhostFormation.computePositions(knight, 1, 0, Team.BLUE, 800f);

    assertThat(ghosts).hasSize(1);
    assertThat(ghosts.get(0)).containsExactly(0f, 0f, 800f);
  }
}
