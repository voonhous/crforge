/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The match-wide settings the movement pass reads, taken from the standard game's published values.
 *
 * @param width number of columns of the routing grid, which turns a route node into a cell
 * @param touchdownRestrictedSideMovement whether a unit defending a touchdown lane keeps its own x
 *     while walking, which the waypoint selector applies
 * @param samePathEpsilon tolerance, in cells, below which a freshly searched route is treated as
 *     the one already held; this build reads it only as a threshold of one
 * @param friendlyOnlyOcclusions whether route preparation consults only its own side's overlay
 *     change flag rather than both
 * @param spawnPathfindReachedRadiusFromSpeed whether the arrival threshold of the two pathfind
 *     states is the matching pathfind speed rather than the fixed 1000 units
 * @param pushbackEndDropsRoute whether the end of a pushback's flight drops the route the entity
 *     held, so that its next walk searches a fresh one from where the pushback left it; a rule of
 *     the game build rather than a published value, off unless set. The battle core's world sets it
 *     as it is built; the legacy grid pathfinding system leaves it off
 */
public record MovementGlobals(
    int width,
    boolean touchdownRestrictedSideMovement,
    int samePathEpsilon,
    boolean friendlyOnlyOcclusions,
    boolean spawnPathfindReachedRadiusFromSpeed,
    boolean pushbackEndDropsRoute) {

  /**
   * The settings of the standard game over a grid of the given width.
   *
   * @param width number of columns of the routing grid
   */
  public static MovementGlobals forStandardArena(int width) {
    return new MovementGlobals(
        width,
        PathfindingGlobals.LOGIC_TOUCHDOWN_RESTRICTED_SIDE_MOVEMENT,
        PathfindingGlobals.PATHFINDING_SAMEPATH_EPSILON,
        PathfindingGlobals.PATHFINDING_FRIENDLYONLY_OCCLUSIONS,
        PathfindingGlobals.LOGIC_SPAWN_PATHFIND_REACHED_RADIUS_FROM_SPEED,
        false);
  }

  /**
   * These settings with the end of a pushback dropping the held route, or keeping it.
   *
   * @param drops true to drop the route when a pushback's flight ends
   * @return the settings with that rule
   */
  public MovementGlobals withPushbackEndDropsRoute(boolean drops) {
    return new MovementGlobals(
        width,
        touchdownRestrictedSideMovement,
        samePathEpsilon,
        friendlyOnlyOcclusions,
        spawnPathfindReachedRadiusFromSpeed,
        drops);
  }
}
