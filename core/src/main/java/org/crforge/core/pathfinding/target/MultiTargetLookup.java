package org.crforge.core.pathfinding.target;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.index.SpatialIndex;

/**
 * Finds the extra targets of a hit that reaches several: the candidate selector's loop cut down,
 * with exclusions of its own.
 *
 * <p>Each lookup makes a fresh query around the owner at the selector's query radius, but without
 * moving the king towers last. It walks the answer in order and skips:
 *
 * <ul>
 *   <li>for an index above 0, what the lookup answers for the index before it - only that one, so a
 *       third extra target may repeat the first;
 *   <li>the owner's reference;
 *   <li>a candidate the validator refuses to take, and a hidden one;
 *   <li>one inside the row's own MinimumRange, the owner's collision radius added and the
 *       candidate's taken off when ranges count from the radius;
 *   <li>one beyond its reach: its radius, the sight range and its crown-tower or building extra;
 *   <li>one clipped behind the owner by the sight clip, unless the owner is a building, and one
 *       clipped sideways by the side clip.
 * </ul>
 *
 * <p>Of the rest, a higher priority wins, then a strictly smaller squared distance, so the earlier
 * of equals stays. The pick is kept only when the validator takes it again and it lies inside the
 * owner's attack range - the attack range, not the sight reach.
 *
 * <p>Unlike the selector it has no alive test, no distance reduction, no tower-like sight range and
 * no lowest hit points rule.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line on the lookup. Held by electro_wizard_tower_defence, whose"
            + " wizard hits the nearest other Knight in range, or its reference again when there"
            + " is none. The recursion for an index above 0 is held by no run: no card asks for a"
            + " third target. A list of unique targets is refused by the owner, as no row sets"
            + " one.")
public final class MultiTargetLookup {

  private MultiTargetLookup() {
    // Utility class
  }

  /**
   * The extra target at an index, or null when there is none.
   *
   * @param t the owner's targeting component
   * @param index the index of the extra target, 0 for the first
   * @param queries the lookup's candidates, the validator and the priority's buff answer
   */
  public static TargetView lookup(TargetingState t, int index, Queries queries) {
    TargetingConfig cfg = t.getConfig();
    int ownerX = t.getOwner().getX();
    int ownerY = t.getOwner().getY();
    int sight = SightRange.sightRange(t);
    if (PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS) {
      sight += cfg.collisionRadius();
    }
    // The row's own minimum range, read raw: no sequence step and no radius added.
    int minimum = cfg.minimumRange();
    int buildingExtra = PathfindingGlobals.EXTRA_SIGHT_RANGE_TO_BUILDING;
    int crownExtra = PathfindingGlobals.EXTRA_SIGHT_RANGE_TO_CROWN_TOWERS;
    List<TargetView> candidates =
        queries.lookupCandidates(ownerX, ownerY, sight + Math.max(crownExtra, buildingExtra));

    TargetView best = null;
    int bestPriority = TargetPriority.ORDINARY;
    int bestDistance = Integer.MAX_VALUE;
    for (TargetView candidate : candidates) {
      // The recursion makes its own query for every candidate, as the game does.
      if (index > 0 && lookup(t, index - 1, queries) == candidate) {
        continue;
      }
      if (candidate == t.getReference()) {
        continue;
      }
      if (!queries.validate(candidate, ReferenceValidator.MODE_TAKE)) {
        continue;
      }
      if (candidate.getHiddenCountdownMs() > 0) {
        continue;
      }
      int squared = RangeTest.squaredDistance(ownerX, ownerY, candidate.x(), candidate.y());
      if (minimum >= 1) {
        int floor = minimum * minimum;
        if (PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS) {
          // The candidate's radius is taken off, where the selector's range test adds it.
          int edge = cfg.collisionRadius() + minimum - candidate.radius();
          floor = edge * edge;
        }
        if (squared < floor) {
          continue;
        }
      }
      int priority = TargetPriority.priority(t, candidate, queries);
      int extra = candidate.crownTower() ? crownExtra : candidate.building() ? buildingExtra : 0;
      int reach = candidate.radius() + sight + extra;
      if (squared > reach * reach) {
        continue;
      }
      int clip = cfg.sightClip();
      if (clip >= 1 && !t.getOwner().isBuilding()) {
        int behind = Math.max(reach - clip, 0);
        int dy = candidate.y() - ownerY;
        if (SpatialIndex.team(t.getOwner()) == 0) {
          if (dy < -behind) {
            continue;
          }
        } else if (dy > behind) {
          continue;
        }
      }
      // The side clip applies whatever the owner.
      int clipSide = cfg.sightClipSide();
      if (clipSide >= 1) {
        int sideways = Math.max(reach - clipSide, 0);
        if (Integer.compareUnsigned(Math.abs(candidate.x() - ownerX), sideways) > 0) {
          continue;
        }
      }
      if (priority > bestPriority) {
        best = candidate;
        bestPriority = priority;
        bestDistance = squared;
      } else if (squared < bestDistance && priority >= bestPriority) {
        best = candidate;
        bestPriority = priority;
        bestDistance = squared;
      }
    }
    queries.releaseCandidates(candidates);
    if (best == null || !queries.validate(best, ReferenceValidator.MODE_TAKE)) {
      return null;
    }
    // The range is the one the owner's reference gives, not the pick's.
    return RangeTest.rangeTest(
            best, ownerX, ownerY, AttackRange.attackRange(t), AttackRange.minRange(t), false)
        ? best
        : null;
  }

  /** What the lookup asks of the battle beyond the selector's questions. */
  public interface Queries extends SelectionQueries {

    /**
     * The entities within a circle around a point, in the spatial index's order, the king towers
     * not moved last.
     *
     * @param x the point along the arena's width
     * @param y the point along the arena's length
     * @param radius the circle's radius
     */
    List<TargetView> lookupCandidates(int x, int y, int radius);
  }
}
