package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Spends one visit's movement budget: steers the entity, lets it be pushed, and walks it along its
 * route.
 *
 * <p>The head of every visit is the same whatever the entity is doing: a movement-forbidding flag
 * drops the route and stops it, the avoidance handler runs if its gate allows, the avoidance blend
 * decays 10 toward zero, the push pass runs if its gate allows, and the budget is asked for. After
 * that the visit splits three ways by state:
 *
 * <ul>
 *   <li>jumping: one displacement along the arc, with the arc's height sampled from how far through
 *       the jump the entity is;
 *   <li>dashing: up to one displacement per 250 units of budget, stopping when the reference comes
 *       into range or the dash runs out, and relocating off water where it lands;
 *   <li>anything else: the ordinary route walk, one displacement per 250 units of budget, popping
 *       the route's last node every time a displacement reports arrival.
 * </ul>
 *
 * <p>A speed of 60 therefore gives exactly one displacement per tick, because 60 divided by 250 is
 * zero and the loop always runs once.
 *
 * <p>The jump and dash branches, and the negative-speed branches of the ordinary path, are written
 * out so that the behaviour is complete, but no plain ground unit reaches any of them and no test
 * here exercises them.
 */
public final class RouteFollower {

  /** Largest distance one displacement may cover, in game units. */
  private static final int SUBSTEP = 250;

  /** Speed below which the ordinary path refuses to move at all. */
  private static final int MIN_SPEED = -249;

  /** Milliseconds one visit adds to the movement time when no scaled step applies. */
  private static final int TICK_MS = 50;

  /** How much the avoidance blend decays toward zero each visit. */
  private static final int BLEND_DECAY = 10;

  /** Largest number of arc samples a jump is divided into. */
  private static final int JUMP_SAMPLES = 32;

  /** Height below which a jump arc sample is not recorded at all. */
  private static final int MIN_ARC_HEIGHT = 16;

  /** Movement is forbidden outright. */
  private static final long NO_MOVE = 1L << 6;

  /** Movement is forbidden except when the entity is pulled by something else. */
  private static final long NO_MOVE_ALLOW_ATTRACT = 1L << 58;

  private RouteFollower() {
    // Utility class
  }

  /**
   * Runs one follower visit.
   *
   * @param component the entity's movement component
   * @param owner the entity being moved
   * @param config the entity's movement configuration columns
   * @param globals the match-wide movement settings
   * @param grid the arena's routing grid
   * @param queries the answers the follower pulls from the rest of the simulation
   * @param chain the chain that runs the passes the follower reaches and records the rest
   */
  public static void follow(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementGlobals globals,
      CellGrid grid,
      MovementQueries queries,
      MovementChain chain) {
    long flags = owner.getFlags();
    if ((flags & (NO_MOVE | NO_MOVE_ALLOW_ATTRACT)) != 0) {
      if (component.getChargeProgress() != MovementState.CHARGE_INACTIVE) {
        if (config.chargeRange() == 0) {
          chain.mark("modifier_component");
          if (queries.hasModifierComponent() && queries.chargeRangeFromModifiers() != 0) {
            component.setChargeProgress(0);
          } else {
            component.setChargeProgress(MovementState.CHARGE_INACTIVE);
          }
        } else {
          component.setChargeProgress(0);
        }
        resetMovementByte(owner, queries, chain);
      }
      component.setRoute(new Route());
      component.setRouteLeadsAway(0);
      int state = owner.getState();
      if (state == GridEntityState.MOVING
          || state == GridEntityState.DASHING
          || state == GridEntityState.JUMPING
          || state == GridEntityState.ROUTE_FOLLOWING_ALTERNATE) {
        chain.mark("set_state_standing");
      }
      if ((flags & NO_MOVE) != 0) {
        return;
      }
    }

    chain.mark("avoidance_gate");
    if ((queries.avoidanceGate() & 1) != 0) {
      chain.mark("avoidance");
    }
    int blend = component.getAvoidanceBlend();
    component.setAvoidanceBlend(
        blend >= 1
            ? Math.max(blend, BLEND_DECAY) - BLEND_DECAY
            : Math.min(blend, -BLEND_DECAY) + BLEND_DECAY);
    chain.mark("push_gate");
    if ((queries.pushGate() & 1) != 0) {
      chain.pushPass();
    }
    chain.mark("speed_budget");
    int speed = queries.speedBudget();
    int state = owner.getState();

    if (state == GridEntityState.JUMPING) {
      jumpVisit(component, owner, config, globals, queries, chain, speed);
      return;
    }
    if (state == GridEntityState.DASHING) {
      dashVisit(component, owner, config, globals, grid, queries, chain, speed);
      return;
    }

    component.setMoveTimeMsCopy(component.getMoveTimeMs());
    if (speed >= 1) {
      chain.mark("modifier_component");
      int increment;
      if (queries.hasModifierComponent()) {
        chain.mark("scaled_time_step");
        increment = FixedMath.divOrZero(queries.scaledTimeStep(), 2);
      } else {
        increment = TICK_MS;
      }
      component.setMoveTimeMs(component.getMoveTimeMs() + increment);
      int limit = config.stopMovementAfterMs();
      if (limit >= 1 && component.getMoveTimeMs() > limit) {
        int wait = config.waitMs();
        if (component.getMoveTimeMs() - (limit + wait) < 0) {
          if (component.getRoute().isEmpty()) {
            tailDisplace(component, owner, config, globals, queries, chain, 0);
            return;
          }
          substepLoop(component, owner, config, globals, grid, queries, chain, 0, 0);
          return;
        }
        component.setMoveTimeMs(component.getMoveTimeMs() - (limit + wait));
        component.setMoveTimeMsCopy(component.getMoveTimeMs());
      }
    }
    if (component.getRoute().isEmpty()) {
      tailDisplace(component, owner, config, globals, queries, chain, speed);
      return;
    }
    if (speed < MIN_SPEED) {
      // Not exercised by tests: nothing in the standard game produces a budget this negative.
      return;
    }
    substepLoop(
        component,
        owner,
        config,
        globals,
        grid,
        queries,
        chain,
        speed,
        FixedMath.divOrZero(speed, SUBSTEP));
  }

  /**
   * The empty-route tail: one displacement toward whatever the waypoint selector answers, which for
   * an empty route is the entity's own position.
   */
  static void tailDisplace(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementGlobals globals,
      MovementQueries queries,
      MovementChain chain,
      int speed) {
    int[] waypoint =
        WaypointSelector.selectWaypoint(component, owner, config, globals, queries, chain);
    component.setScratch(waypoint[0], waypoint[1]);
    chain.mark("facing_gate");
    step(chain, component, owner, waypoint, speed, queries.facingGate() & 1, 0);
  }

  /**
   * The ordinary route walk: up to {@code substepLimit + 1} displacements of at most 250 units,
   * consuming a route node every time one reports arrival.
   */
  static void substepLoop(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementGlobals globals,
      CellGrid grid,
      MovementQueries queries,
      MovementChain chain,
      int speed,
      int substepLimit) {
    int iteration = 0;
    int remaining = speed;
    while (true) {
      int[] waypoint =
          WaypointSelector.selectWaypoint(component, owner, config, globals, queries, chain);
      component.setScratch(waypoint[0], waypoint[1]);
      int nextRemaining = remaining - SUBSTEP;
      int stepSize = speed > SUBSTEP - 1 ? Math.min(remaining, SUBSTEP) : remaining;
      chain.mark("facing_gate");
      step(chain, component, owner, waypoint, stepSize, queries.facingGate() & 1, 0);
      if (component.getWaypointReached() != 0) {
        component.getRoute().pop();
        chain.directionInit();
        if (config.jumpEnabled()) {
          crossWaterByJumping(component, owner, globals, grid, chain);
        }
      }
      if (iteration < substepLimit && !component.getRoute().isEmpty()) {
        iteration++;
        remaining = nextRemaining;
        continue;
      }
      return;
    }
  }

  /**
   * The jump-enabled water crossing: when the node just uncovered is water, the entity jumps to the
   * centre of the first node below it that is not.
   *
   * <p>Not exercised by tests: only the five jump-enabled cards reach it.
   */
  private static void crossWaterByJumping(
      MovementState component,
      GridEntity owner,
      MovementGlobals globals,
      CellGrid grid,
      MovementChain chain) {
    Route route = component.getRoute();
    int count = route.size();
    if (count < 2) {
      return;
    }
    int width = globals.width();
    if (!isWaterNode(grid, width, route.get(count - 1))) {
      return;
    }
    int index = count - 2;
    while (index > 0 && isWaterNode(grid, width, route.get(index))) {
      index--;
    }
    if (index == 0 && isWaterNode(grid, width, route.get(0))) {
      index = 0;
    }
    int node = route.get(index);
    int targetX = (node % width) * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
    int targetY = (node / width) * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
    chain.mark("set_jump_target");
    int squared = FixedMath.guardedSumOfSquares(targetX - owner.getX(), targetY - owner.getY());
    component.setJumpTotalDistance(
        squared == FixedMath.INT_MAX ? FixedMath.SATURATED_DISTANCE : FixedMath.isqrt(squared));
    chain.mark("set_state_jumping");
  }

  /** True when the cell a route node names holds water. */
  private static boolean isWaterNode(CellGrid grid, int width, int node) {
    return (grid.water(node % width, node / width) & 1) != 0;
  }

  /**
   * One visit while the entity follows a jump arc.
   *
   * <p>Not exercised by tests: only the five jump-enabled cards reach it.
   */
  private static void jumpVisit(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementGlobals globals,
      MovementQueries queries,
      MovementChain chain,
      int speed) {
    if (speed == 0) {
      return;
    }
    int jumpHeight = config.jumpHeight();
    int[] waypoint =
        WaypointSelector.selectWaypoint(component, owner, config, globals, queries, chain);
    step(chain, component, owner, waypoint, speed, 1, 0);
    int total = component.getJumpTotalDistance();
    if (component.getPushbackBudget() != 0) {
      int squared =
          FixedMath.guardedSumOfSquares(owner.getX() - waypoint[0], owner.getY() - waypoint[1]);
      if (squared > total * total) {
        total =
            squared == FixedMath.INT_MAX ? FixedMath.SATURATED_DISTANCE : FixedMath.isqrt(squared);
        component.setJumpTotalDistance(total);
      }
    }
    int steps = FixedMath.divOrZero(total, speed);
    int distance =
        FixedMath.guardedDistance(owner.getX() - waypoint[0], owner.getY() - waypoint[1]);
    int done = FixedMath.divOrZero(distance, speed);
    int position = Math.min(Math.min(done, steps - done) * 2, JUMP_SAMPLES);
    if (done <= 1) {
      chain.mark("set_state_moving");
      return;
    }
    position = Math.max(position, 0);
    int height = (position - ((position * position) >> 6)) * jumpHeight;
    int z = owner.getZ();
    if (Integer.compareUnsigned(height, MIN_ARC_HEIGHT) < 0) {
      return;
    }
    int sample = height >> 4;
    component.getJumpHeights().add(sample);
    component.getJumpAbsoluteHeights().add(z + sample);
  }

  /**
   * One visit while the entity runs a dash.
   *
   * <p>Not exercised by tests: only the dashing cards reach it.
   */
  private static void dashVisit(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementGlobals globals,
      CellGrid grid,
      MovementQueries queries,
      MovementChain chain,
      int speed) {
    if (speed == 0) {
      return;
    }
    chain.mark("reference_available");
    boolean hasReference = queries.referenceAvailable() != 0;
    if (speed >= MIN_SPEED) {
      int substeps = FixedMath.divOrZero(speed, SUBSTEP) + 1;
      // The divisor is chosen with an unsigned comparison, so a negative speed is kept as it is.
      int denominator = Integer.compareUnsigned(speed, 1) > 0 ? speed : 1;
      int remaining = speed;
      int constantTime = config.dashConstantTime();
      while (true) {
        int[] waypoint =
            WaypointSelector.selectWaypoint(component, owner, config, globals, queries, chain);
        int nextRemaining = remaining - SUBSTEP;
        int stepSize = speed > SUBSTEP - 1 ? Math.min(remaining, SUBSTEP) : remaining;
        chain.mark("facing_gate");
        step(chain, component, owner, waypoint, stepSize, queries.facingGate() & 1, 0);
        if (constantTime >= 1) {
          component.setWaypointReached(component.getDashTimeMs() < 1 ? 1 : 0);
        }
        int x = owner.getX();
        int y = owner.getY();
        int z = owner.getZTotal();
        int distance = FixedMath.guardedDistance(x - waypoint[0], y - waypoint[1]);
        int guard;
        if (constantTime >= 1) {
          guard = FixedMath.divOrZero(component.getDashTimeMs(), TICK_MS);
          int twoThirds = FixedMath.divOrZero(config.jumpHeight() * 2, 3);
          int peak = config.jumpHeight();
          int progress = constantTime - component.getDashTimeMs();
          int quarter = FixedMath.divOrZero(constantTime, 4);
          int half = constantTime / 2;
          int threeQuarters = FixedMath.divOrZero(constantTime * 3, 4);
          if (progress - quarter < 0) {
            z = FixedMath.divOrZero(progress * twoThirds, quarter);
          } else if (progress - half < 0) {
            z =
                FixedMath.divOrZero((progress - quarter) * (peak - twoThirds), half - quarter)
                    + twoThirds;
          } else if (threeQuarters - progress > 0) {
            z =
                FixedMath.divOrZero((progress - half) * (twoThirds - peak), threeQuarters - half)
                    + peak;
          } else if (progress < constantTime) {
            z =
                FixedMath.divOrZero(
                        (threeQuarters - progress) * twoThirds, constantTime - threeQuarters)
                    + twoThirds;
          } else {
            z = 0;
          }
        } else {
          guard = FixedMath.divOrZero(distance, denominator);
        }
        boolean stop = false;
        if (hasReference && component.getDashStopsInRange() != 0) {
          chain.mark("targeting_active");
          if (queries.targetingActive()) {
            chain.mark("targeting_active");
            chain.mark("reference_in_range");
            if ((queries.referenceInRange() & 1) != 0) {
              stop = true;
            }
          }
        }
        if (!stop && guard < 1) {
          stop = true;
        }
        if (stop) {
          component.setRoute(new Route());
          component.setRouteLeadsAway(0);
          chain.mark("on_stop");
          if (x >= 0
              && y >= 0
              && x < grid.getWidth() * TileMap.CELL_UNITS
              && y < grid.getHeight() * TileMap.CELL_UNITS
              && (grid.water(x / TileMap.CELL_UNITS, y / TileMap.CELL_UNITS) & 1) != 0) {
            chain.mark("relocate");
            int packed = queries.relocate(x, y);
            x = packed & 0xffff;
            y = packed >> 16;
          }
          owner.setX(x);
          owner.setY(y);
          owner.setZ(0);
          break;
        }
        owner.setX(x);
        owner.setY(y);
        owner.setZ(z);
        remaining = nextRemaining;
        substeps--;
        if (substeps == 0) {
          break;
        }
      }
    }
    if (config.dashConstantTime() >= 1) {
      component.setDashTimeMs(component.getDashTimeMs() - TICK_MS);
    }
  }

  /** Runs one displacement through the chain and copies its outcome back onto the shared state. */
  private static void step(
      MovementChain chain,
      MovementState component,
      GridEntity owner,
      int[] waypoint,
      int budget,
      int updateFacing,
      int attackFlag) {
    MovementOutcome outcome =
        chain.displace(waypoint[0], waypoint[1], budget, updateFacing, attackFlag);
    owner.setX(outcome.x());
    owner.setY(outcome.y());
    component.setWaypointReached(outcome.reached());
  }

  /** Clears the entity's movement byte, which the targeting side reads. */
  private static void resetMovementByte(
      GridEntity owner, MovementQueries queries, MovementChain chain) {
    chain.mark("movement_byte");
    if (queries.targetingSlotZero()) {
      owner.setSlot37(0);
    }
  }
}
