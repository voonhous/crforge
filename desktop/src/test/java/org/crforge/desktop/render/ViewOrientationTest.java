package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.EntityView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The view's orientation: standing it draws the battle's positions as they are, flipped it mirrors
 * the arena along its length only, and either way the side at the bottom is blue, named blue and
 * first in the HUD, and a pixel maps back to the battle's own tile and cell.
 */
class ViewOrientationTest {

  private static final TileMap MAP = TileMap.standard1v1();
  private static final int WIDTH = MAP.widthUnits();
  private static final int HEIGHT = MAP.heightUnits();

  @Test
  @DisplayName("standing, a position is drawn where the battle holds it")
  void standingIsTheIdentity() {
    ViewOrientation view = ViewOrientation.STANDARD;

    assertThat(view.flipped()).isFalse();
    assertThat(view.x(3500)).isEqualTo(3500);
    assertThat(view.y(8500)).isEqualTo(8500);
    assertThat(view.px(3500)).isEqualTo(RenderConstants.unitsToPixels(3500));
    assertThat(view.py(8500))
        .isEqualTo(RenderConstants.unitsToPixels(8500) + RenderConstants.BOTTOM_UI_HEIGHT);
    assertThat(view.dx(1f)).isEqualTo(1f);
    assertThat(view.dy(-1f)).isEqualTo(-1f);
  }

  @Test
  @DisplayName(
      "flipped, a position is mirrored along the length only: a play on the right stays on the right")
  void flippedMirrorsTheLengthOnly() {
    ViewOrientation view = ViewOrientation.FLIPPED;

    assertThat(view.flipped()).isTrue();
    assertThat(view.x(3500)).isEqualTo(3500);
    assertThat(view.x(14500)).isEqualTo(14500);
    assertThat(view.y(8500)).isEqualTo(HEIGHT - 8500);
    assertThat(view.x(WIDTH / 2)).isEqualTo(WIDTH / 2);
    assertThat(view.y(HEIGHT / 2)).isEqualTo(HEIGHT / 2);
    assertThat(view.px(3500)).isEqualTo(RenderConstants.unitsToPixels(3500));
    assertThat(view.py(8500))
        .isEqualTo(RenderConstants.unitsToPixels(HEIGHT - 8500) + RenderConstants.BOTTOM_UI_HEIGHT);
    // A direction of travel along the length reverses; along the width it stays.
    assertThat(view.dx(1f)).isEqualTo(1f);
    assertThat(view.dy(-1f)).isEqualTo(1f);
    // Twice flipped is standing.
    assertThat(view.toggled()).isEqualTo(ViewOrientation.STANDARD);
    assertThat(ViewOrientation.STANDARD.toggled()).isEqualTo(view);
  }

  @Test
  @DisplayName("flipped, a cell's square is drawn where its mirror stands: the first cell top left")
  void flippedCellSquares() {
    ViewOrientation view = ViewOrientation.FLIPPED;
    int cell = TileMap.CELL_UNITS;
    float arenaTop =
        RenderConstants.BOTTOM_UI_HEIGHT + RenderConstants.unitsToPixels(MAP.heightUnits());
    float arenaRight = RenderConstants.unitsToPixels(MAP.widthUnits());

    assertThat(view.left(0, cell)).isZero();
    assertThat(view.bottom(0, cell)).isEqualTo(arenaTop - RenderConstants.CELL_PIXELS);
    assertThat(view.left((MAP.width() - 1) * cell, cell))
        .isEqualTo(arenaRight - RenderConstants.CELL_PIXELS);
    assertThat(view.bottom((MAP.height() - 1) * cell, cell))
        .isEqualTo(RenderConstants.BOTTOM_UI_HEIGHT);
    assertThat(ViewOrientation.STANDARD.left(0, cell)).isZero();
    assertThat(ViewOrientation.STANDARD.bottom(0, cell))
        .isEqualTo(RenderConstants.BOTTOM_UI_HEIGHT);
  }

  @Test
  @DisplayName("the side at the bottom is drawn blue, named blue and the HUD's first")
  void theBottomSideIsBlue() {
    ViewOrientation standing = ViewOrientation.STANDARD;
    assertThat(standing.bottomSide()).isZero();
    assertThat(standing.topSide()).isEqualTo(1);
    assertThat(standing.blue(0)).isTrue();
    assertThat(standing.blue(1)).isFalse();
    assertThat(standing.sideName(0)).isEqualTo("blue");
    assertThat(standing.sideName(1)).isEqualTo("red");
    assertThat(standing.atTop(1)).isTrue();
    assertThat(standing.sidesBottomFirst()).containsExactly(0, 1);
    assertThat(standing.statusLine()).isEqualTo("view: side 0 at bottom (F flips)");

    ViewOrientation flipped = ViewOrientation.FLIPPED;
    assertThat(flipped.bottomSide()).isEqualTo(1);
    assertThat(flipped.topSide()).isZero();
    assertThat(flipped.blue(1)).isTrue();
    assertThat(flipped.blue(0)).isFalse();
    assertThat(flipped.sideName(1)).isEqualTo("blue");
    assertThat(flipped.sideName(0)).isEqualTo("red");
    assertThat(flipped.atTop(0)).isTrue();
    assertThat(flipped.atTop(1)).isFalse();
    assertThat(flipped.sidesBottomFirst()).containsExactly(1, 0);
    assertThat(flipped.statusLine()).isEqualTo("view: side 1 at bottom (F flips)");
  }

  @Test
  @DisplayName("flipped, a battle's side 0 king stands at the top and side 1's at the bottom")
  void flippedKingsSwapEnds() {
    BattleFrame frame = BattleAdapter.frame(BattleSession.ladder(GameTables.loadConfigured()));
    float middle =
        RenderConstants.BOTTOM_UI_HEIGHT + RenderConstants.unitsToPixels(MAP.heightUnits()) / 2;
    for (EntityView tower : frame.entities()) {
      if (tower.kind() != EntityView.Kind.TOWER || !tower.king()) {
        continue;
      }
      boolean sideZero = tower.side() == 0;
      assertThat(ViewOrientation.STANDARD.py(tower.y()) < middle)
          .as("standing, side %d's king at the bottom", tower.side())
          .isEqualTo(sideZero);
      assertThat(ViewOrientation.FLIPPED.py(tower.y()) < middle)
          .as("flipped, side %d's king at the bottom", tower.side())
          .isEqualTo(!sideZero);
      // The battle's position is untouched by either view.
      assertThat(tower.y() < MAP.heightUnits() / 2).isEqualTo(sideZero);
    }
  }

  @Test
  @DisplayName("a pixel maps back to the battle's own tile and cell, whichever way up")
  void pixelsMapBackToTheBattlesTile() {
    for (ViewOrientation view :
        new ViewOrientation[] {ViewOrientation.STANDARD, ViewOrientation.FLIPPED}) {
      for (int tileX = 0; tileX < view.tilesWide(); tileX++) {
        for (int tileY = 0; tileY < view.tilesLong(); tileY++) {
          // The pixel at the drawn centre of the tile's centre point.
          float pixelX = view.px(GameUnits.tileCenter(tileX));
          float pixelY = view.py(GameUnits.tileCenter(tileY));
          assertThat(view.tileColumnAt(pixelX)).isEqualTo(tileX);
          assertThat(view.tileRowAt(pixelY)).isEqualTo(tileY);
          // The tile centre is a cell corner; a quarter tile in lies in cell 2 * tile.
          int quarter = GameUnits.UNITS_PER_TILE / 4;
          int cellX = 2 * tileX;
          int cellY = 2 * tileY;
          assertThat(view.cellColumnAt(view.px(tileX * GameUnits.UNITS_PER_TILE + quarter)))
              .isEqualTo(cellX);
          assertThat(view.cellRowAt(view.py(tileY * GameUnits.UNITS_PER_TILE + quarter)))
              .isEqualTo(cellY);
        }
      }
      // A pixel off the arena gives a tile off it.
      float below = RenderConstants.BOTTOM_UI_HEIGHT - 5;
      int row = view.tileRowAt(below);
      assertThat(row < 0 || row >= view.tilesLong()).as("%s: off the arena", view).isTrue();
      int column = view.tileColumnAt(-5);
      assertThat(column < 0 || column >= view.tilesWide()).as("%s: off the arena", view).isTrue();
    }
  }
}
