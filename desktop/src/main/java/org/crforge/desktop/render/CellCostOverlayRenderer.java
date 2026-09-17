package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.CELL_PIXELS;
import static org.crforge.desktop.render.RenderConstants.COLOR_CELL_HOVER;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.GridPathfindingSystem;
import org.crforge.core.pathfinding.grid.CellCostField;
import org.crforge.core.pathfinding.grid.CellCosts;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.FootprintOverlay;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.player.Team;

/**
 * Paints the arena's routing grid, one filled square per 500-unit cell, coloured by what the route
 * search would charge a unit to enter it.
 *
 * <p>The cost of a cell is not one number: it depends on the unit asking. This overlay prices every
 * cell for one fixed unit and says so in the status column - a plain ground unit of the blue side,
 * in the moving state, assigned to lane 1, with neither water permission. That choice matters as
 * follows:
 *
 * <ul>
 *   <li><b>ground, no water permission</b> - the river costs the blocked weight rather than the
 *       water weight, which is what makes an ordinary troop walk to a bridge. Air units are not
 *       driven by the routing grid, so no managed unit has a water permission today;
 *   <li><b>the moving state</b> - the three pathfinding states charge every cell the default
 *       weight, which would hide the roads entirely. Moving is the state a troop walks in;
 *   <li><b>lane 1</b> - a cell carrying the unit's own road and a cell carrying the other road cost
 *       the same on the standard arena, so the lane changes nothing here; it is pinned only so the
 *       overlay answers for a definite unit;
 *   <li><b>the blue side</b> - the side plays no part in the cost rule at all. It is named for
 *       completeness, because the unit the overlay describes has to be somebody.
 * </ul>
 *
 * <p>The grid is the live one when the match runs under the grid rules, so a building deployed
 * mid-match shows up. Note that the overlay a tick stamps is rotated into the grid's previous slot
 * at the end of that tick, so between ticks the stamps live there and the current slot is a fresh
 * zeroed array; this renderer reads the slot that carries the stamps.
 *
 * <p>Under the waypoint rules there is no routing grid at all. The overlay then builds its own from
 * the arena's static cell map with the match's towers stamped into it, so the terrain and the tower
 * footprints are still visible. It is rebuilt whenever the set of standing towers changes, so a
 * destroyed tower stops occluding.
 *
 * <p>No number is drawn per cell - that would be 2304 glyphs a frame. The cell under the mouse is
 * outlined and its column, row, cost and class are printed in the status column instead.
 */
public class CellCostOverlayRenderer {

  /** The lane the priced unit is assigned to. */
  private static final int OVERLAY_LANE = 1;

  /** The state the priced unit is in. */
  private static final int OVERLAY_STATE = GridEntityState.MOVING;

  private final RenderContext ctx;

  private final CellCosts costs = CellCosts.standard();

  /** The grid built for a match that has no routing grid of its own, or null before the first. */
  private CellGrid staticGrid;

  /** Which towers {@link #staticGrid} was stamped from, so it can be rebuilt when they change. */
  private String staticGridTowers;

  public CellCostOverlayRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Fills every cell of the arena and outlines the one under the mouse.
   *
   * @param engine the running engine
   * @param hoverCol the column under the mouse, or a value outside the grid when there is none
   * @param hoverRow the row under the mouse
   */
  public void render(GameEngine engine, int hoverCol, int hoverRow) {
    Snapshot snapshot = snapshot(engine);
    CellGrid grid = snapshot.grid();

    Gdx.gl.glEnable(GL20.GL_BLEND);
    ctx.getShapeRenderer().begin(ShapeType.Filled);
    for (int row = 0; row < grid.getHeight(); row++) {
      for (int col = 0; col < grid.getWidth(); col++) {
        ctx.getShapeRenderer().setColor(snapshot.classOf(col, row).color());
        ctx.getShapeRenderer()
            .rect(
                col * CELL_PIXELS, row * CELL_PIXELS + BOTTOM_UI_HEIGHT, CELL_PIXELS, CELL_PIXELS);
      }
    }
    ctx.getShapeRenderer().end();

    if (snapshot.inside(hoverCol, hoverRow)) {
      ctx.getShapeRenderer().begin(ShapeType.Line);
      ctx.getShapeRenderer().setColor(COLOR_CELL_HOVER);
      ctx.getShapeRenderer()
          .rect(
              hoverCol * CELL_PIXELS,
              hoverRow * CELL_PIXELS + BOTTOM_UI_HEIGHT,
              CELL_PIXELS,
              CELL_PIXELS);
      ctx.getShapeRenderer().end();
    }
  }

  /**
   * The status line for the cell under the mouse: its column and row, what it costs the priced unit
   * and why. Never null; a cell outside the arena says so.
   */
  public String hoverStatus(GameEngine engine, int hoverCol, int hoverRow) {
    Snapshot snapshot = snapshot(engine);
    String where = "cell (" + hoverCol + ", " + hoverRow + ")";
    if (!snapshot.inside(hoverCol, hoverRow)) {
      return where + " " + CellCostClass.OUT_OF_ARENA.name().toLowerCase(Locale.ROOT);
    }
    return where
        + " cost "
        + snapshot.cost(hoverCol, hoverRow)
        + " "
        + snapshot.classOf(hoverCol, hoverRow).name().toLowerCase(Locale.ROOT);
  }

  /** The grid and the footprint overlay this frame prices cells against. */
  private Snapshot snapshot(GameEngine engine) {
    GridPathfindingSystem system = engine.getGridPathfindingSystem();
    if (system != null) {
      CellGrid grid = system.getGrid();
      return new Snapshot(costs, grid, grid.getPrevious(), grid.getActive() != 0);
    }
    CellGrid grid = staticGrid(engine.getGameState());
    return new Snapshot(costs, grid, grid.getCurrent(), grid.getActive() != 0);
  }

  /**
   * The grid used when the match has none: the arena's static cell map with the standing towers
   * stamped into it. Rebuilt only when the standing towers change.
   */
  private CellGrid staticGrid(GameState state) {
    List<Tower> towers = standingTowers(state);
    StringBuilder signature = new StringBuilder();
    for (Tower tower : towers) {
      signature
          .append(tower.getPosition().getX())
          .append(':')
          .append(tower.getPosition().getY())
          .append(':')
          .append(tower.getCollisionRadius())
          .append('|');
    }
    String key = signature.toString();
    if (staticGrid != null && key.equals(staticGridTowers)) {
      return staticGrid;
    }
    CellGrid grid =
        new CellGrid(
            TileMap.standard1v1(),
            PathfindingGlobals.PATHFINDING_DYNAMIC_OCCLUSIONS,
            PathfindingGlobals.PATHFINDING_BUILDING_COST);
    grid.setActive(1);
    for (Tower tower : towers) {
      int radius = tower.getCollisionRadius();
      FootprintOverlay.rasterize(
          grid,
          tower.getPosition().getX(),
          tower.getPosition().getY(),
          radius,
          radius,
          grid.getBuildingCost());
    }
    staticGrid = grid;
    staticGridTowers = key;
    return grid;
  }

  /** The match's standing towers, king first for each side, in a stable order. */
  private static List<Tower> standingTowers(GameState state) {
    List<Tower> towers = new ArrayList<>();
    for (Team team : List.of(Team.BLUE, Team.RED)) {
      Tower crown = state.getCrownTower(team);
      if (crown != null && crown.isAlive()) {
        towers.add(crown);
      }
      for (Tower princess : state.getPrincessTowers(team)) {
        if (princess.isAlive()) {
          towers.add(princess);
        }
      }
    }
    return towers;
  }

  /**
   * One frame's view of the grid: the cell map, the footprint overlay that is in force and whether
   * it is in force at all.
   */
  private record Snapshot(CellCosts costs, CellGrid grid, int[] overlay, boolean overlayActive) {

    private boolean inside(int col, int row) {
      return col >= 0 && row >= 0 && col < grid.getWidth() && row < grid.getHeight();
    }

    private int overlayCost(int col, int row) {
      return overlay[row * grid.getWidth() + col];
    }

    private int cost(int col, int row) {
      return CellCostField.cellCost(
          grid.getWidth(),
          grid.getHeight(),
          col,
          row,
          grid.tiles(col, row),
          costs,
          true,
          false,
          false,
          OVERLAY_STATE,
          OVERLAY_LANE,
          overlayActive,
          overlayCost(col, row));
    }

    private CellCostClass classOf(int col, int row) {
      if (!inside(col, row)) {
        return CellCostClass.OUT_OF_ARENA;
      }
      return CellCostClass.classify(
          true, grid.tiles(col, row), overlayActive, overlayCost(col, row), grid.getBuildingCost());
    }
  }
}
