/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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
 * The view's orientation, the game's view of one side: the view of side 1 mirrors the arena along
 * its length only, the view of side 0 is that view turned half a turn (the width mirrored only, as
 * the battle's coordinates are drawn with y up), and either way the side at the bottom is blue,
 * named blue and first in the HUD, and a pixel maps back to the battle's own tile and cell.
 */
class ViewOrientationTest {

  private static final TileMap MAP = TileMap.standard1v1();
  private static final int WIDTH = MAP.widthUnits();
  private static final int HEIGHT = MAP.heightUnits();

  @Test
  @DisplayName(
      "the game's view of side 0: a side 0 play at x 14500 is drawn on the left, near the bottom")
  void sideZeroViewDrawsAPlayTurnedHalfATurn() {
    ViewOrientation view = ViewOrientation.SIDE_0;

    // As drawn: x mirrored across the width, y as the battle holds it (side 0 at the bottom).
    assertThat(view.x(14500)).isEqualTo(WIDTH - 14500);
    assertThat(view.y(6500)).isEqualTo(6500);
    assertThat(view.px(14500)).isEqualTo(RenderConstants.unitsToPixels(WIDTH - 14500));
    assertThat(view.py(6500))
        .isEqualTo(RenderConstants.unitsToPixels(6500) + RenderConstants.BOTTOM_UI_HEIGHT);
    float arenaMiddleX = RenderConstants.unitsToPixels(WIDTH) / 2;
    float arenaMiddleY =
        RenderConstants.BOTTOM_UI_HEIGHT + RenderConstants.unitsToPixels(HEIGHT) / 2;
    assertThat(view.px(14500)).isLessThan(arenaMiddleX);
    assertThat(view.py(6500)).isLessThan(arenaMiddleY);
    // The screen point maps back to the battle's own tile and cell.
    assertThat(view.tileColumnAt(view.px(14500))).isEqualTo(14);
    assertThat(view.tileRowAt(view.py(6500))).isEqualTo(6);
    assertThat(view.cellColumnAt(view.px(14750))).isEqualTo(29);
    assertThat(view.cellRowAt(view.py(6750))).isEqualTo(13);
    // A heading turns with the view: both components reversed against the side 1 view.
    assertThat(view.dx(1f)).isEqualTo(-1f);
    assertThat(view.dy(1f)).isEqualTo(1f);
    assertThat(view.dx(1f)).isEqualTo(-ViewOrientation.SIDE_1.dx(1f));
    assertThat(view.dy(1f)).isEqualTo(-ViewOrientation.SIDE_1.dy(1f));
  }

  @Test
  @DisplayName(
      "the game's view of side 1: a side 0 play at x 14500 is drawn on the right, near the top")
  void sideOneViewDrawsAPlayAsItIs() {
    ViewOrientation view = ViewOrientation.SIDE_1;

    assertThat(view.x(14500)).isEqualTo(14500);
    assertThat(view.y(6500)).isEqualTo(HEIGHT - 6500);
    assertThat(view.px(14500)).isEqualTo(RenderConstants.unitsToPixels(14500));
    assertThat(view.py(6500))
        .isEqualTo(RenderConstants.unitsToPixels(HEIGHT - 6500) + RenderConstants.BOTTOM_UI_HEIGHT);
    assertThat(view.tileColumnAt(view.px(14500))).isEqualTo(14);
    assertThat(view.tileRowAt(view.py(6500))).isEqualTo(6);
    assertThat(view.cellColumnAt(view.px(14750))).isEqualTo(29);
    assertThat(view.cellRowAt(view.py(6750))).isEqualTo(13);
    assertThat(view.dx(1f)).isEqualTo(1f);
    assertThat(view.dy(1f)).isEqualTo(-1f);
  }

  @Test
  @DisplayName("the two game views are each other's half turn, and toggling twice is the same view")
  void theTwoViewsAreHalfATurnApart() {
    ViewOrientation sideZero = ViewOrientation.SIDE_0;
    ViewOrientation sideOne = ViewOrientation.SIDE_1;

    assertThat(sideZero.toggled()).isEqualTo(sideOne);
    assertThat(sideOne.toggled()).isEqualTo(sideZero);
    assertThat(sideZero.toggled().toggled()).isEqualTo(sideZero);
    for (int x : new int[] {0, 3500, 9000, 14500, WIDTH}) {
      for (int y : new int[] {0, 6500, 16000, 25500, HEIGHT}) {
        // Each point of one view stands where the other view's point is, turned half a turn.
        assertThat(sideZero.x(x)).isEqualTo(WIDTH - sideOne.x(x));
        assertThat(sideZero.y(y)).isEqualTo(HEIGHT - sideOne.y(y));
      }
    }
    int cell = TileMap.CELL_UNITS;
    for (int col = 0; col < MAP.width(); col++) {
      float arenaRight = RenderConstants.unitsToPixels(WIDTH);
      assertThat(sideZero.left(col * cell, cell))
          .isEqualTo(arenaRight - sideOne.left(col * cell, cell) - RenderConstants.CELL_PIXELS);
    }
  }

  @Test
  @DisplayName(
      "the view of side 0 mirrors the width only: a play on the right is drawn on the left")
  void sideZeroMirrorsTheWidthOnly() {
    ViewOrientation view = ViewOrientation.SIDE_0;

    assertThat(view.bottomSide()).isZero();
    assertThat(view.x(3500)).isEqualTo(WIDTH - 3500);
    assertThat(view.y(8500)).isEqualTo(8500);
    assertThat(view.x(WIDTH / 2)).isEqualTo(WIDTH / 2);
    assertThat(view.y(HEIGHT / 2)).isEqualTo(HEIGHT / 2);
    assertThat(view.px(3500)).isEqualTo(RenderConstants.unitsToPixels(WIDTH - 3500));
    assertThat(view.py(8500))
        .isEqualTo(RenderConstants.unitsToPixels(8500) + RenderConstants.BOTTOM_UI_HEIGHT);
    // A direction of travel along the width reverses; along the length it stays.
    assertThat(view.dx(1f)).isEqualTo(-1f);
    assertThat(view.dy(-1f)).isEqualTo(-1f);
  }

  @Test
  @DisplayName("the view of side 1 mirrors the length only: a play on the right stays on the right")
  void sideOneMirrorsTheLengthOnly() {
    ViewOrientation view = ViewOrientation.SIDE_1;

    assertThat(view.bottomSide()).isEqualTo(1);
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
    // Toggled, it is the view of side 0, and toggled back, itself.
    assertThat(view.toggled()).isEqualTo(ViewOrientation.SIDE_0);
    assertThat(ViewOrientation.SIDE_0.toggled()).isEqualTo(view);
  }

  @Test
  @DisplayName(
      "a cell's square is drawn where its mirror stands: the first cell top left from side 1,"
          + " bottom right from side 0")
  void cellSquares() {
    ViewOrientation view = ViewOrientation.SIDE_1;
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
    ViewOrientation sideZero = ViewOrientation.SIDE_0;
    assertThat(sideZero.left(0, cell)).isEqualTo(arenaRight - RenderConstants.CELL_PIXELS);
    assertThat(sideZero.bottom(0, cell)).isEqualTo(RenderConstants.BOTTOM_UI_HEIGHT);
    assertThat(sideZero.left((MAP.width() - 1) * cell, cell)).isZero();
    assertThat(sideZero.bottom((MAP.height() - 1) * cell, cell))
        .isEqualTo(arenaTop - RenderConstants.CELL_PIXELS);
    // A span narrower than a cell keeps its own edge: the first 100 units from side 0 stand at
    // the arena's right edge.
    assertThat(sideZero.left(0, 100)).isEqualTo(arenaRight - RenderConstants.unitsToPixels(100));
  }

  @Test
  @DisplayName("the side at the bottom is drawn blue, named blue and the HUD's first")
  void theBottomSideIsBlue() {
    ViewOrientation sideZero = ViewOrientation.SIDE_0;
    assertThat(sideZero.bottomSide()).isZero();
    assertThat(sideZero.topSide()).isEqualTo(1);
    assertThat(sideZero.blue(0)).isTrue();
    assertThat(sideZero.blue(1)).isFalse();
    assertThat(sideZero.sideName(0)).isEqualTo("blue");
    assertThat(sideZero.sideName(1)).isEqualTo("red");
    assertThat(sideZero.atTop(1)).isTrue();
    assertThat(sideZero.sidesBottomFirst()).containsExactly(0, 1);
    assertThat(sideZero.statusLine()).isEqualTo("view: side 0 at bottom (F flips)");

    ViewOrientation sideOne = ViewOrientation.SIDE_1;
    assertThat(sideOne.bottomSide()).isEqualTo(1);
    assertThat(sideOne.topSide()).isZero();
    assertThat(sideOne.blue(1)).isTrue();
    assertThat(sideOne.blue(0)).isFalse();
    assertThat(sideOne.sideName(1)).isEqualTo("blue");
    assertThat(sideOne.sideName(0)).isEqualTo("red");
    assertThat(sideOne.atTop(0)).isTrue();
    assertThat(sideOne.atTop(1)).isFalse();
    assertThat(sideOne.sidesBottomFirst()).containsExactly(1, 0);
    assertThat(sideOne.statusLine()).isEqualTo("view: side 1 at bottom (F flips)");
  }

  @Test
  @DisplayName(
      "each side's king stands at the bottom of its own view and at the top of the other side's")
  void kingsStandAtTheirOwnViewsBottom() {
    BattleFrame frame = BattleAdapter.frame(BattleSession.ladder(GameTables.loadConfigured()));
    float middle =
        RenderConstants.BOTTOM_UI_HEIGHT + RenderConstants.unitsToPixels(MAP.heightUnits()) / 2;
    for (EntityView tower : frame.entities()) {
      if (tower.kind() != EntityView.Kind.TOWER || !tower.king()) {
        continue;
      }
      boolean sideZero = tower.side() == 0;
      assertThat(ViewOrientation.SIDE_0.py(tower.y()) < middle)
          .as("side 0's view, side %d's king at the bottom", tower.side())
          .isEqualTo(sideZero);
      assertThat(ViewOrientation.SIDE_1.py(tower.y()) < middle)
          .as("side 1's view, side %d's king at the bottom", tower.side())
          .isEqualTo(!sideZero);
      // The battle's position is untouched by either view.
      assertThat(tower.y() < MAP.heightUnits() / 2).isEqualTo(sideZero);
    }
  }

  @Test
  @DisplayName("a pixel maps back to the battle's own tile and cell, in either side's view")
  void pixelsMapBackToTheBattlesTile() {
    for (ViewOrientation view :
        new ViewOrientation[] {ViewOrientation.SIDE_0, ViewOrientation.SIDE_1}) {
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
