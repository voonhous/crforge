package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Decides where a unit is heading and makes sure it holds a route that gets there.
 *
 * <p>The destination is the first of three that applies: an explicit destination set on the
 * movement component, the stopping cell chosen near the unit's reference, or a destination a game
 * mode forces on a unit with no reference. Without any of the three the unit keeps whatever route
 * it has.
 *
 * <p>A route already held is reused untouched when its goal is still the destination, unless the
 * cost overlay changed this tick and the unit walks on the ground. Even then the route it already
 * holds may survive the replan: {@link RouteRetention} decides.
 *
 * <p>A unit that does not walk on the ground never searches at all; it gets a single-node route
 * straight to the destination cell.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line; held by the five reference walks for"
            + " a unit with a tower as its reference. Not held: the friendly-only occlusion"
            + " path, a same-path epsilon below one, and a replan that finds no route.")
public final class RoutePreparation {

  private RoutePreparation() {
    // Utility class
  }

  /**
   * Chooses a destination and stores a route to it on the movement component.
   *
   * @param component the movement component whose route and route-leads-away bit this writes
   * @param owner the entity being routed
   * @param grid the arena's routing grid, which supplies the width, the change flags and the two
   *     cost overlays
   * @param globals the match-wide movement settings
   * @param reference the position the unit is heading for, or null when it has none
   * @param queries the answers route preparation pulls from the rest of the simulation
   * @param chain the chain that runs the direction initializer and records what was announced
   */
  public static void prepareRoute(
      MovementState component,
      GridEntity owner,
      CellGrid grid,
      MovementGlobals globals,
      ReferencePoint reference,
      MovementQueries queries,
      MovementChain chain) {
    int width = grid.getWidth();
    int goalCol;
    int goalRow;
    int explicitX = component.getExplicitX();
    int explicitY = component.getExplicitY();
    if (explicitX >= 0 && explicitY >= 0) {
      goalCol = explicitX / TileMap.CELL_UNITS;
      goalRow = explicitY / TileMap.CELL_UNITS;
    } else if (reference != null) {
      chain.mark("endpoint");
      int radius = queries.attackRange();
      int packed =
          queries.endpoint(
              reference.x() / TileMap.CELL_UNITS, reference.y() / TileMap.CELL_UNITS, radius);
      if (packed < 0) {
        // The scan found no cell. The raw reference position is used as the goal instead; it is in
        // game units rather than cells, which is what the standard game does here.
        goalCol = reference.x();
        goalRow = reference.y();
      } else {
        goalCol = packed >> 16;
        goalRow = packed & 0xffff;
      }
    } else {
      chain.mark("game_mode_goal");
      int packed = queries.gameModeGoal();
      if (packed < 0) {
        return;
      }
      goalCol = packed >> 16;
      goalRow = packed & 0xffff;
    }

    boolean overlayChanged;
    if (globals.friendlyOnlyOcclusions()) {
      chain.mark("owner_side");
      int side = queries.ownerSide();
      // The side is compared without a sign, so anything outside 0..1 reads as unchanged.
      overlayChanged = Integer.compareUnsigned(side, 1) <= 0 && grid.getChangeFlags()[side] != 0;
    } else {
      overlayChanged = grid.getChangeFlags()[0] != 0 || grid.getChangeFlags()[1] != 0;
    }
    chain.mark("ground");
    int ground = queries.ground() & 1;

    Route route = component.getRoute();
    boolean goalChanged = true;
    if (route.size() >= 1) {
      int goalNode = route.get(0);
      if (goalCol == goalNode % width && goalRow == goalNode / width) {
        if (!(overlayChanged && ground != 0)) {
          return;
        }
        goalChanged = false;
      }
    }
    component.setRouteLeadsAway(0);
    if (ground == 0) {
      component.setRoute(Route.of(goalRow * width + goalCol));
      chain.directionInit();
      return;
    }

    boolean compare = !goalChanged && globals.samePathEpsilon() >= 1;
    Route saved = compare ? route.copy() : new Route();
    int startCol = FixedMath.div(owner.getX(), TileMap.CELL_UNITS);
    int startRow = FixedMath.div(owner.getY(), TileMap.CELL_UNITS);
    chain.mark("search");
    Route found = queries.search(startCol, startRow, goalCol, goalRow, 1);
    chain.mark("search_notify");
    chain.mark("search_stats");
    queries.searchCounter();

    // Adjacent duplicates are dropped. The comparison starts below every valid node id, so a
    // leading node of -1 is dropped as well.
    Route deduplicated = new Route(Math.max(found.size(), 1));
    int previous = -1;
    for (int index = 0; index < found.size(); index++) {
      int node = found.get(index);
      if (node == previous) {
        continue;
      }
      previous = node;
      deduplicated.add(node);
    }
    component.setRoute(deduplicated);
    if (saved.size() > 0
        && !RouteRetention.overlayChangeAffectsRoutes(
            grid.getCurrent(), grid.getPrevious(), saved, deduplicated)) {
      component.setRoute(saved);
    }
    if (reference != null) {
      chain.mark("farther");
      component.setRouteLeadsAway(queries.farther() & 1);
    }
    if (component.getRoute().size() >= 1) {
      chain.directionInit();
    }
  }
}
