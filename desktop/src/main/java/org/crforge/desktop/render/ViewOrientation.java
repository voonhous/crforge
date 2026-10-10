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
 * Which way up the visualizer draws the arena, and the one place every arena position, team colour
 * and side name goes through on its way to the screen. It is a view only: the battle's sides, its
 * positions and its inputs are never changed by it.
 *
 * <p>Standing, side 0 is at the bottom and drawn blue, side 1 at the top and drawn red, as the
 * battle's own coordinates have it (y grows from side 0 towards side 1). Flipped, the arena is
 * mirrored along its length only, as the game's own replay view draws it: a position keeps its
 * place along the width (a play on the right stays on the right) and moves to the other end of the
 * length, so side 1 stands at the bottom. The side at the bottom is always the one drawn blue and
 * named "blue", and its hand, elixir and crowns take the bottom panel.
 *
 * @param flipped whether the arena is drawn mirrored along its length, side 1 at the bottom
 * @param widthUnits the arena's width in game units, which a flip leaves as it is
 * @param heightUnits the arena's length in game units, which a flip mirrors positions across
 */
public record ViewOrientation(boolean flipped, int widthUnits, int heightUnits) {

  /** The standard arena standing: side 0 at the bottom, as the original screens draw it. */
  public static final ViewOrientation STANDARD = of(TileMap.standard1v1(), false);

  /** The standard arena flipped: side 1 at the bottom. */
  public static final ViewOrientation FLIPPED = of(TileMap.standard1v1(), true);

  /**
   * The orientation of an arena.
   *
   * @param tileMap the arena's tile map, whose size a flip mirrors across
   * @param flipped whether side 1 is drawn at the bottom
   */
  public static ViewOrientation of(TileMap tileMap, boolean flipped) {
    return new ViewOrientation(flipped, tileMap.widthUnits(), tileMap.heightUnits());
  }

  /** The other orientation of the same arena. */
  public ViewOrientation toggled() {
    return new ViewOrientation(!flipped, widthUnits, heightUnits);
  }

  /** The side drawn at the bottom, blue, with the bottom HUD panel. */
  public int bottomSide() {
    return flipped ? 1 : 0;
  }

  /** The side drawn at the top, red, with the top HUD panel. */
  public int topSide() {
    return 1 - bottomSide();
  }

  /** Whether a side's HUD panel is the top one. */
  public boolean atTop(int side) {
    return side == topSide();
  }

  /** Whether a side is drawn in blue: the side at the bottom. */
  public boolean blue(int side) {
    return side == bottomSide();
  }

  /** The name the screen gives a side: "blue" for the side at the bottom, else "red". */
  public String sideName(int side) {
    return blue(side) ? "blue" : "red";
  }

  /** The sides in the order the HUD lists them: the bottom (blue) side first. */
  public List<Integer> sidesBottomFirst() {
    return List.of(bottomSide(), topSide());
  }

  /** A position along the width as drawn, in game units: a flip leaves it as it is. */
  public int x(int x) {
    return x;
  }

  /** A position along the length as drawn, in game units. */
  public int y(int y) {
    return flipped ? heightUnits - y : y;
  }

  /** A position along the width as drawn, in game units: a flip leaves it as it is. */
  public float x(float x) {
    return x;
  }

  /** A position along the length as drawn, in game units. */
  public float y(float y) {
    return flipped ? heightUnits - y : y;
  }

  /** The window's pixel column of a position along the width given in game units. */
  public float px(float x) {
    return unitsToPixels(x(x));
  }

  /** The window's pixel row of a position along the length given in game units. */
  public float py(float y) {
    return unitsToPixels(y(y)) + BOTTOM_UI_HEIGHT;
  }

  /** A direction along the width as drawn: a flip leaves it as it is. */
  public float dx(float dx) {
    return dx;
  }

  /** A direction along the length as drawn: reversed by a flip. */
  public float dy(float dy) {
    return flipped ? -dy : dy;
  }

  /**
   * The pixel column of the left edge of a span along the width as drawn: the span from {@code
   * start} to {@code start + size} game units, which a flip leaves where it is.
   */
  public float left(int start, int size) {
    return unitsToPixels(start);
  }

  /**
   * The pixel row of the bottom edge of a span along the length as drawn: the span from {@code
   * start} to {@code start + size} game units, whose ends a flip swaps.
   */
  public float bottom(int start, int size) {
    return unitsToPixels(flipped ? heightUnits - start - size : start) + BOTTOM_UI_HEIGHT;
  }

  /**
   * The arena tile column under a window pixel column: the battle's column, whichever way up the
   * arena is drawn. A pixel off the arena gives a column off it.
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

  /** A column of {@code columns} as drawn, or the drawn column back: a flip leaves it as it is. */
  private int column(int column, int columns) {
    return column;
  }

  /**
   * A row of {@code rows} as drawn, or the drawn row back to the battle's: a flip numbers them from
   * the other end, which is its own inverse. A row off the arena stays off it.
   */
  private int row(int row, int rows) {
    return flipped ? rows - 1 - row : row;
  }

  /** The status column's line for the orientation, naming the key that flips it. */
  public String statusLine() {
    return "view: side " + bottomSide() + " at bottom (F flips)";
  }
}
