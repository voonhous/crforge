package org.crforge.core.pathfinding.target;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.index.SpatialIndex;

/**
 * Picks the target a unit attacks, among the entities around it.
 *
 * <p>The order is fixed: three early exits, then a circle query around the unit, then one pass over
 * the candidates that filters and ranks them, and finally the fallbacks. Ties in the ranking fall
 * to the candidate that came first out of the query, so the bucket order of the spatial index
 * decides them.
 *
 * <p>Distances are compared squared. Two of the comparisons are unsigned, which matters when a
 * candidate sits far enough away for the squared distance to saturate; those use {@link
 * Integer#compareUnsigned(int, int)}.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The loop, the ranking, the tie-breaks, the clips and the fallbacks agree with"
            + " the reference. Held with towers as the only candidates. Not held by any"
            + " fixture: enemy troops as candidates, lowest hit points, sight clipping,"
            + " minimum range and the touchdown rule. A hidden candidate is skipped on its"
            + " countdown alone, without asking whether it carries the component the reference"
            + " also requires.")
public final class CandidateSelector {

  /** The entity state of a unit that is dashing. */
  private static final int STATE_DASHING = 3;

  /** The entity state of a unit that is following a jump arc. */
  private static final int STATE_JUMPING = 5;

  private CandidateSelector() {
    // Utility class
  }

  /**
   * Chooses a reference for the component and stores it.
   *
   * <p>The selection returns without choosing when a buff locks the current reference in place,
   * when the owner is dashing or jumping, or when the owner carries the target lock flag.
   *
   * @param t the targeting component
   * @param useAll true to rank every entity the index holds instead of the ones within sight
   * @param buildingsOnly true to consider buildings only
   * @param queries supplies the candidates, the validator and the default target
   * @param outcome collects the resume and route requests the store makes
   */
  public static void select(
      TargetingState t,
      boolean useAll,
      boolean buildingsOnly,
      SelectionQueries queries,
      TargetingOutcome outcome) {
    if (t.getReference() != null && t.getTargetLockingBuffs() > 0) {
      return;
    }
    int state = t.getOwner().getState();
    if (state == STATE_DASHING || state == STATE_JUMPING) {
      return;
    }
    if ((t.getOwner().getFlags() & EntityFlags.LOCK_TARGET) != 0) {
      return;
    }

    TargetingConfig cfg = t.getConfig();
    int sight = SightRange.sightRange(t);
    if (PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS) {
      sight += cfg.collisionRadius();
    }
    int minimum = AttackRange.minRange(t);
    int buildingExtra = PathfindingGlobals.EXTRA_SIGHT_RANGE_TO_BUILDING;
    int crownExtra = PathfindingGlobals.EXTRA_SIGHT_RANGE_TO_CROWN_TOWERS;

    List<TargetView> candidates;
    if (useAll) {
      candidates = queries.allCandidates();
    } else {
      candidates =
          queries.candidates(
              t.getOwner().getX(),
              t.getOwner().getY(),
              Math.max(crownExtra, buildingExtra) + sight);
    }

    TargetView best = null;
    int bestDistance = Integer.MAX_VALUE;
    int bestPriority = TargetPriority.ORDINARY;
    int bestHitPoints = Integer.MAX_VALUE;

    for (TargetView candidate : candidates) {
      if (!queries.validate(candidate, ReferenceValidator.MODE_TAKE)) {
        continue;
      }
      if (!candidate.alive()) {
        continue;
      }
      if (buildingsOnly && !candidate.building()) {
        continue;
      }
      if (candidate.getHiddenCountdownMs() > 0) {
        continue;
      }
      if (minimum >= 1
          && !RangeTest.rangeTest(
              candidate,
              t.getOwner().getX(),
              t.getOwner().getY(),
              0,
              cfg.collisionRadius() + minimum,
              true)) {
        continue;
      }
      int squared =
          RangeTest.squaredDistance(
              t.getOwner().getX(), t.getOwner().getY(), candidate.x(), candidate.y());
      int reduction = candidate.squaredDistanceReduction();
      int reach = candidate.radius() + sight;
      boolean towerLike = false;
      if (!cfg.isBuilding()) {
        TargetingConfig candidateConfig = candidate.getConfig();
        if (candidate.presenceFlag()
            && candidateConfig != null
            && candidateConfig.sightRangeForCharacters() >= 1) {
          reach = Math.max(candidateConfig.sightRangeForCharacters(), cfg.range());
          reach += candidate.radius();
          if (cfg.dashCooldown() > 0) {
            reach = Math.max(reach, cfg.dashMaxRange());
            reach += candidate.radius();
          }
          towerLike = true;
        }
      }
      int extra = candidate.crownTower() ? crownExtra : candidate.building() ? buildingExtra : 0;
      int total = extra + reach;
      int reduced = Math.max(squared - reduction, 0);
      if (Integer.compareUnsigned(reduced, total * total) > 0 && !useAll) {
        continue;
      }
      int clip = cfg.sightClip();
      if (!(clip < 1 || useAll || towerLike || t.getOwner().isBuilding())) {
        int behind = Math.max(total - clip, 0);
        int dy = candidate.y() - t.getOwner().getY();
        if (SpatialIndex.team(t.getOwner()) == 0) {
          // The top side looks down the arena, so a candidate too far behind it is ignored.
          if (dy < -behind) {
            continue;
          }
        } else if (dy > behind) {
          continue;
        }
      }
      int clipSide = cfg.sightClipSide();
      if (!(towerLike || useAll || clipSide < 1)) {
        int sideways = Math.max(total - clipSide, 0);
        if (Integer.compareUnsigned(Math.abs(candidate.x() - t.getOwner().getX()), sideways) > 0) {
          continue;
        }
      }
      int priority = TargetPriority.priority(t, candidate, queries);
      if (cfg.targetLowestHp()) {
        int hitPoints =
            candidate.isHitPointsPresent() ? candidate.getHitPoints() : Integer.MAX_VALUE;
        if (hitPoints < bestHitPoints) {
          bestHitPoints = hitPoints;
          best = candidate;
        }
        continue;
      }
      if (priority > bestPriority) {
        best = candidate;
        bestDistance = reduced;
        bestPriority = priority;
      } else if (reduced < bestDistance && priority >= bestPriority) {
        best = candidate;
        bestDistance = reduced;
        bestPriority = priority;
      }
    }
    queries.releaseCandidates(candidates);

    if (best != null) {
      ReferenceSetter.setReference(t, best, false, true, false, queries, outcome);
      return;
    }
    TargetView chosen = null;
    if (t.getReference() != null
        && t.isMovementComponentActive()
        && t.isRouteLeadsAway()
        && PathfindingGlobals.LOGIC_PATHFIND_BACKWARDS_TRY_KEEP_TARGET
        && queries.validate(t.getReference(), ReferenceValidator.MODE_TAKE)) {
      chosen = t.getReference();
    }
    if (chosen == null) {
      chosen = queries.defaultTarget();
    }
    if (queries.validate(chosen, ReferenceValidator.MODE_TAKE)) {
      ReferenceSetter.setReference(t, chosen, false, true, false, queries, outcome);
      return;
    }
    if (queries.touchdownMode()) {
      ReferenceSetter.setReference(t, null, false, true, false, queries, outcome);
    }
  }
}
