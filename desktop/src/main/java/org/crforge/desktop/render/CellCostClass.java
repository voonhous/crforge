package org.crforge.desktop.render;

import com.badlogic.gdx.graphics.Color;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * Why a routing cell costs what it costs, and the colour the cell-cost overlay paints it in.
 *
 * <p>The cost on its own is not enough to colour a cell: a water cell a unit may not enter and a
 * cell under a building footprint both charge the same blocked weight, and they are worth telling
 * apart. So the overlay classifies a cell from its static flags and the footprint overlay, in the
 * same order the cost rule applies them:
 *
 * <ol>
 *   <li>a cell outside the arena has no cost at all;
 *   <li>a water cell is water, even when it also carries a road;
 *   <li>a cell with the blocked flag is blocked;
 *   <li>a cell a building footprint covers is a building cell, even when it also carries a road,
 *       because the footprint raises the cost above whatever the cell would otherwise charge;
 *   <li>a cell carrying a road is a road cell;
 *   <li>everything else is plain ground.
 * </ol>
 */
public enum CellCostClass {

  /** Outside the arena: the cost rule rejects the cell outright. */
  OUT_OF_ARENA(RenderConstants.COLOR_CELL_OUT_OF_ARENA),

  /** The river. A unit without a water permission is charged the blocked weight to enter it. */
  WATER(RenderConstants.COLOR_CELL_WATER),

  /** Flagged blocked by the arena's cell map, which charges the blocked weight. */
  BLOCKED(RenderConstants.COLOR_CELL_BLOCKED),

  /** Covered by a building's footprint, which raises the cell to the building weight. */
  BUILDING(RenderConstants.COLOR_CELL_BUILDING),

  /** Carries a road, which is cheaper than plain ground. */
  ROAD(RenderConstants.COLOR_CELL_ROAD),

  /** Plain ground with no road and nothing stamped over it. */
  DEFAULT(RenderConstants.COLOR_CELL_DEFAULT);

  private final Color color;

  CellCostClass(Color color) {
    this.color = color;
  }

  /** The colour the overlay fills a cell of this class with. */
  public Color color() {
    return color;
  }

  /**
   * Classifies one cell.
   *
   * @param insideArena whether the column and row address a cell of the arena's cell map
   * @param tileBits the cell's static routing flag word
   * @param overlayActive whether the building footprint overlay has been built and is in force
   * @param overlayCost the overlay's value for this cell
   * @param buildingCost the value a footprint stamps, which is what marks a cell as covered
   */
  public static CellCostClass classify(
      boolean insideArena, int tileBits, boolean overlayActive, int overlayCost, int buildingCost) {
    if (!insideArena) {
      return OUT_OF_ARENA;
    }
    if ((tileBits & TileMap.WATER_BIT) != 0) {
      return WATER;
    }
    if ((tileBits & TileMap.BLOCKED_BIT) != 0) {
      return BLOCKED;
    }
    if (overlayActive && overlayCost >= buildingCost) {
      return BUILDING;
    }
    return (tileBits & TileMap.ROAD_ID_MASK) != 0 ? ROAD : DEFAULT;
  }
}
