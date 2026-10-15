/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.unitsToPixels;

import java.util.List;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;

/**
 * Which way up the visualizer draws the arena, and the one place every arena position, direction,
 * team colour and side name goes through on its way to the screen. It is a view only: the battle's
 * sides, its positions and its inputs are never changed by it.
 *
 * <p>There are two orientations, and both are the game's own views of the arena: the view of side 1
 * and the view of side 0, each named by the side it draws at the bottom. The battle's own
 * coordinates have y grow from side 0 towards side 1, and the window's y grows up.
 *
 * <ul>
 *   <li>The game's view of side 1 ({@link #SIDE_1}) draws the battle's positions as they are on the
 *       game's screen, whose y grows down: side 1 stands at the bottom and a position keeps its
 *       place along the width (a play on the right stays on the right), so here only the length is
 *       mirrored. The game shows a replay that neither player is watching, such as a TV replay,
 *       this way.
 *   <li>The game's view of side 0 ({@link #SIDE_0}) is that view turned half a turn about the
 *       arena's centre: side 0 stands at the bottom, as the battle's coordinates have it, and a
 *       position moves to the other side of the width (a play on the right as side 1 sees it is on
 *       the left as side 0 sees it), so here only the width is mirrored.
 * </ul>
 *
 * <p>The game never draws the battle's coordinates as they are with y up, side 0 at the bottom and
 * the width kept, nor mirrors the length alone against them. Either way the side at the bottom is
 * the one drawn blue and named "blue", and its hand, elixir and crowns take the bottom panel.
 *
 * @param bottomSide the side drawn at the bottom, blue, with the bottom HUD panel: 0 or 1, the side
 *     whose game view this is
 * @param widthUnits the arena's width in game units, which the view of side 0 mirrors positions
 *     across
 * @param heightUnits the arena's length in game units, which the view of side 1 mirrors positions
 *     across
 */
public record ViewOrientation(int bottomSide, int widthUnits, int heightUnits) {

  /** The game's view of side 0 on the standard arena: side 0 at the bottom, the width mirrored. */
  public static final ViewOrientation SIDE_0 = of(TileMap.standard1v1(), 0);

  /** The game's view of side 1 on the standard arena: side 1 at the bottom, the length mirrored. */
  public static final ViewOrientation SIDE_1 = of(TileMap.standard1v1(), 1);

  /** Refuses a bottom side that is neither 0 nor 1. */
  public ViewOrientation {
    if (bottomSide != 0 && bottomSide != 1) {
      throw new IllegalArgumentException("a view's bottom side is 0 or 1, not " + bottomSide);
    }
  }

  /**
   * The game's view of one side of an arena.
   *
   * @param tileMap the arena's tile map, whose size the view mirrors positions across
   * @param bottomSide the side drawn at the bottom, 0 or 1
   */
  public static ViewOrientation of(TileMap tileMap, int bottomSide) {
    return new ViewOrientation(bottomSide, tileMap.widthUnits(), tileMap.heightUnits());
  }

  /** The game's view of the other side of the same arena: this view turned half a turn. */
  public ViewOrientation toggled() {
    return new ViewOrientation(1 - bottomSide, widthUnits, heightUnits);
  }

  /** The side drawn at the top, red, with the top HUD panel. */
  public int topSide() {
    return 1 - bottomSide;
  }

  /** Whether a side's HUD panel is the top one. */
  public boolean atTop(int side) {
    return side == topSide();
  }

  /** Whether a side is drawn in blue: the side at the bottom. */
  public boolean blue(int side) {
    return side == bottomSide;
  }

  /** The name the screen gives a side: "blue" for the side at the bottom, else "red". */
  public String sideName(int side) {
    return blue(side) ? "blue" : "red";
  }

  /** The sides in the order the HUD lists them: the bottom (blue) side first. */
  public List<Integer> sidesBottomFirst() {
    return List.of(bottomSide, topSide());
  }

  /** Whether positions are mirrored across the width: in the view of side 0. */
  private boolean mirrorsWidth() {
    return bottomSide == 0;
  }

  /** Whether positions are mirrored across the length: in the view of side 1. */
  private boolean mirrorsLength() {
    return bottomSide == 1;
  }

  /** A position along the width as drawn, in game units: mirrored in the view of side 0. */
  public int x(int x) {
    return mirrorsWidth() ? widthUnits - x : x;
  }

  /** A position along the length as drawn, in game units: mirrored in the view of side 1. */
  public int y(int y) {
    return mirrorsLength() ? heightUnits - y : y;
  }

  /** A position along the width as drawn, in game units: mirrored in the view of side 0. */
  public float x(float x) {
    return mirrorsWidth() ? widthUnits - x : x;
  }

  /** A position along the length as drawn, in game units: mirrored in the view of side 1. */
  public float y(float y) {
    return mirrorsLength() ? heightUnits - y : y;
  }

  /** The window's pixel column of a position along the width given in game units. */
  public float px(float x) {
    return unitsToPixels(x(x));
  }

  /** The window's pixel row of a position along the length given in game units. */
  public float py(float y) {
    return unitsToPixels(y(y)) + BOTTOM_UI_HEIGHT;
  }

  /** A direction along the width as drawn: reversed in the view of side 0. */
  public float dx(float dx) {
    return mirrorsWidth() ? -dx : dx;
  }

  /** A direction along the length as drawn: reversed in the view of side 1. */
  public float dy(float dy) {
    return mirrorsLength() ? -dy : dy;
  }

  /**
   * The pixel column of the left edge of a span along the width as drawn: the span from {@code
   * start} to {@code start + size} game units, whose ends the view of side 0 swaps.
   */
  public float left(int start, int size) {
    return unitsToPixels(mirrorsWidth() ? widthUnits - start - size : start);
  }

  /**
   * The pixel row of the bottom edge of a span along the length as drawn: the span from {@code
   * start} to {@code start + size} game units, whose ends the view of side 1 swaps.
   */
  public float bottom(int start, int size) {
    return unitsToPixels(mirrorsLength() ? heightUnits - start - size : start) + BOTTOM_UI_HEIGHT;
  }

  /**
   * The arena tile column under a window pixel column: the battle's column, whichever side's view
   * is drawn. A pixel off the arena gives a column off it.
   */
  public int tileColumnAt(float pixelX) {
    return column((int) Math.floor(pixelX / RenderConstants.TILE_PIXELS), tilesWide());
  }

  /** The arena tile row under a window pixel row (the bottom panel included), as the battle's. */
  public int tileRowAt(float pixelY) {
    return row(
        (int) Math.floor((pixelY - BOTTOM_UI_HEIGHT) / RenderConstants.TILE_PIXELS), tilesLong());
  }

  /** The routing cell column under a window pixel column, as the battle's grid numbers it. */
  public int cellColumnAt(float pixelX) {
    return column(
        (int) Math.floor(pixelX / RenderConstants.CELL_PIXELS), widthUnits / TileMap.CELL_UNITS);
  }

  /** The routing cell row under a window pixel row (the bottom panel included). */
  public int cellRowAt(float pixelY) {
    return row(
        (int) Math.floor((pixelY - BOTTOM_UI_HEIGHT) / RenderConstants.CELL_PIXELS),
        heightUnits / TileMap.CELL_UNITS);
  }

  /** The arena's width in tiles. */
  public int tilesWide() {
    return widthUnits / GameUnits.UNITS_PER_TILE;
  }

  /** The arena's length in tiles. */
  public int tilesLong() {
    return heightUnits / GameUnits.UNITS_PER_TILE;
  }

  /**
   * A column of {@code columns} as drawn, or the drawn column back to the battle's: the view of
   * side 0 numbers them from the other end, which is its own inverse. A column off the arena stays
   * off it.
   */
  private int column(int column, int columns) {
    return mirrorsWidth() ? columns - 1 - column : column;
  }

  /**
   * A row of {@code rows} as drawn, or the drawn row back to the battle's: the view of side 1
   * numbers them from the other end, which is its own inverse. A row off the arena stays off it.
   */
  private int row(int row, int rows) {
    return mirrorsLength() ? rows - 1 - row : row;
  }

  /** The status column's line for the orientation, naming the key that flips it. */
  public String statusLine() {
    return "view: side " + bottomSide + " at bottom (F flips)";
  }
}
