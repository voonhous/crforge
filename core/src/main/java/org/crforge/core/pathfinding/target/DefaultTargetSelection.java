/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.target;

import java.util.List;
import java.util.function.Predicate;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The target a unit walks toward when nothing nearer is worth attacking.
 *
 * <p>The selection starts from a seed - the opposing side's king tower, which fills its tower slot
 * - and ranks that side's registered map objects: its princess towers, in the order they were
 * placed, left then right. The king is never a candidate, and ordinary troops and the buildings
 * placed during a battle never enter the list; a princess tower leaves it at the cleanup of the
 * tick it dies. With both princess towers standing, a unit in its own half takes its lane's one.
 *
 * <p>Two rankings exist. With {@link PathfindingGlobals#LOGIC_XPOS_BASED_TOWER_TARGETING} the
 * candidate with the smallest difference in x wins and is then checked against the seed's own
 * distance; otherwise the candidates are ranked by an approximate squared distance with an optional
 * bonus for a matching lane. The published values select the x-position rule.
 *
 * <p>The two measures differ: a candidate is scored by the square of its approximate distance,
 * which overestimates a separation off the axes by up to about eight per cent, while the seed's
 * threshold is the square of its true distance. A candidate that is nearer only by the
 * approximation therefore does not beat the seed.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The published switches, the candidate filter, the smallest-offset rule, the ranking and"
            + " the lane rule's operand - the unit's elapsed time, so a unit is kept to its own"
            + " lane's towers for its first ten walking visits - agree with the reference, and"
            + " the walks of the reference battles hold them, the Knight walks smoke-c1/knight,"
            + " knight_centre_s0, knight_behind_king_s0 and golden-gaps-v1/walk_knight_right_rear"
            + " with the princess towers as the only candidates and the king as the seed; the lane"
            + " rule's first ten visits by golden-gaps-v1/walk_knight_left_inner; the seed's"
            + " threshold, its true squared distance against a candidate's squared approximate"
            + " one, by random-battles-16-v1/random_battle16_s0049 (a Mighty Miner across in the"
            + " other lane, its lane's princess tower gone, keeps walking at the king). The"
            + " alternate seed, the goal mode and the six-object branch are not held by any"
            + " fixture.")
public final class DefaultTargetSelection {

  /** Score no candidate can reach, used as the starting threshold. */
  private static final int NO_SCORE = Integer.MAX_VALUE;

  /**
   * Elapsed time, in milliseconds, below which a unit is kept to the candidates of its own lane.
   * The elapsed time grows by one step per state visit outside the deploying states and starts at
   * zero, so a fresh unit may only cross to another lane's tower from its eleventh walking visit.
   */
  private static final int LANE_RESTRICTION_TIME_MS = 500;

  /** Lane bonus given near the arena's two back lines. */
  private static final int LANE_BONUS_AT_BACK_LINE = 1000;

  /** Lane bonus given everywhere else. */
  private static final int LANE_BONUS_IN_THE_FIELD = 125;

  /** Depth, in cells, of the two back-line bands that raise the lane bonus. */
  private static final int BACK_LINE_BAND_CELLS = 3;

  private DefaultTargetSelection() {
    // Utility class
  }

  /**
   * The four balance switches the selection reads.
   *
   * @param disablePathLaneId true when route cell ids carry no lane, which lets the alternate seed
   *     be taken whatever its lane
   * @param princessTowersAlwaysDefault true when princess towers are always preferred
   * @param xPositionBasedTowerTargeting true to pick the candidate closest in x rather than to rank
   *     by distance
   * @param useLaneId true to give a matching lane a bonus in the distance ranking
   */
  public record Rules(
      boolean disablePathLaneId,
      boolean princessTowersAlwaysDefault,
      boolean xPositionBasedTowerTargeting,
      boolean useLaneId) {

    /** The published values of the standard game. */
    public static Rules standard() {
      return new Rules(
          PathfindingGlobals.DISABLE_PATH_LINEID,
          PathfindingGlobals.LOGIC_PRINCESS_TOWERS_ALWAYS_AS_DEFAULT_TARGET,
          PathfindingGlobals.LOGIC_XPOS_BASED_TOWER_TARGETING,
          PathfindingGlobals.LOGIC_DEFAULT_TARGET_USE_LANE_ID);
    }
  }

  /** The outcome of one ranking pass: what was chosen and the threshold left behind. */
  public record Ranking(TargetView selected, int threshold) {}

  /**
   * Approximate length of a two-axis separation: the larger absolute component plus 53/128 of the
   * smaller. It overestimates a true diagonal by about four per cent, which is enough to order
   * candidates.
   */
  public static int approxDistance(int dx, int dy) {
    return FixedMath.approxDistance(dx, dy);
  }

  /**
   * Ranks the candidates by their approximate squared distance, keeping the lowest score.
   *
   * <p>A candidate that scores at or above the running threshold is skipped. A candidate that beats
   * the threshold lowers it <b>even when the validator refuses it</b>, so a refused candidate still
   * shuts out the ones behind it; this is the game's behaviour and must not be tidied into a
   * filter-then-minimum loop.
   *
   * @param unitX unit position along the arena's width
   * @param unitY unit position along the arena's length
   * @param unitLane lane the unit was assigned when it was created
   * @param arenaHeightCells arena length in routing cells, used by the lane bonus
   * @param candidates the candidates in registration order
   * @param incumbent the selection to keep when nothing beats the threshold
   * @param threshold the score a candidate has to beat
   * @param useLaneId true to give a matching lane a bonus
   * @param requireMatchingLane true to skip candidates in another lane outright
   * @param validate answers whether a candidate may be taken
   */
  public static Ranking rankCandidates(
      int unitX,
      int unitY,
      int unitLane,
      int arenaHeightCells,
      List<TargetView> candidates,
      TargetView incumbent,
      int threshold,
      boolean useLaneId,
      boolean requireMatchingLane,
      Predicate<TargetView> validate) {
    TargetView selected = incumbent;
    int running = threshold;
    for (TargetView candidate : candidates) {
      int lane = candidate.getEntity().getLane();
      int distance = approxDistance(candidate.x() - unitX, candidate.y() - unitY);
      int score = distance * distance;
      if (useLaneId) {
        int unitRow = unitY / 500;
        int bonus = 0;
        if (unitLane == lane) {
          bonus =
              unitRow < BACK_LINE_BAND_CELLS || unitRow >= arenaHeightCells - BACK_LINE_BAND_CELLS
                  ? LANE_BONUS_AT_BACK_LINE
                  : LANE_BONUS_IN_THE_FIELD;
        }
        score -= bonus * (bonus + 2 * distance);
      }
      if (requireMatchingLane && unitLane != lane) {
        continue;
      }
      if (score >= running) {
        continue;
      }
      if (validate.test(candidate)) {
        selected = candidate;
      }
      running = score;
    }
    return new Ranking(selected, running);
  }

  /**
   * Picks the target a unit falls back to, in the order the rules apply:
   *
   * <ol>
   *   <li>an alternate seed may replace the seed;
   *   <li>a seed answering the tower flag may be dropped;
   *   <li>a mode with a goal of its own answers the original seed;
   *   <li>a mode with a special object list ranks that list instead;
   *   <li>otherwise the side's towers are ranked, by x position or by distance.
   * </ol>
   *
   * @param unitX unit position along the arena's width
   * @param unitY unit position along the arena's length
   * @param unitLane lane the unit was assigned when it was created
   * @param elapsedMs how long the unit has been out of its deploying states, in milliseconds, as it
   *     stands at the targeting visit, before this tick's state visit adds its step
   * @param arenaHeightCells arena length in routing cells
   * @param seed the opposing side's king tower
   * @param candidates the opposing side's registered towers, in placement order, king included
   * @param rules the four balance switches
   * @param queries the game-mode answers
   * @param validate answers whether a candidate may be taken
   */
  public static TargetView selectDefaultTarget(
      int unitX,
      int unitY,
      int unitLane,
      int elapsedMs,
      int arenaHeightCells,
      TargetView seed,
      List<TargetView> candidates,
      Rules rules,
      DefaultSelectionQueries queries,
      Predicate<TargetView> validate) {
    TargetView initialSeed = seed;
    TargetView current = seed;
    if (queries.alternateSeedActive()) {
      TargetView alternate = queries.alternateSeed();
      if (current != null && current.alive()) {
        if (alternate != null
            && alternate.alive()
            && (rules.disablePathLaneId() || alternate.getEntity().getLane() == unitLane)) {
          current = alternate;
        }
      } else if (alternate != null && alternate.alive()) {
        current = alternate;
      }
    }
    if (current != null && current.towerFlag() && queries.suppressTowerSeed()) {
      current = null;
    }
    if (queries.alternateGoalMode() && !queries.specialObjectsActive()) {
      return initialSeed;
    }
    if (queries.specialObjectsActive()) {
      int threshold = seedDistanceScore(current, unitX, unitY);
      for (TargetView object : queries.specialObjects()) {
        if (!object.alive()
            || object.getEntity().getSide() == queries.unitSide()
            || configKeyMatches(object, queries.excludedConfigKey())) {
          continue;
        }
        int distance = approxDistance(object.x() - unitX, object.y() - unitY);
        int score = distance * distance;
        if (score < threshold) {
          current = object;
          threshold = score;
        }
      }
      return current;
    }

    int expected = queries.expectedCandidateCount();
    if (candidates.size() != expected
        && !rules.princessTowersAlwaysDefault()
        && queries.alternateSeedActive()) {
      return current;
    }
    int threshold = NO_SCORE;
    if (!queries.suppressTowerSeed() && !queries.pluginOverride() && current != null) {
      threshold = seedDistanceScore(current, unitX, unitY);
    }
    if (rules.xPositionBasedTowerTargeting()) {
      if (candidates.isEmpty()) {
        return current;
      }
      boolean restrictToLane = !queries.suppressTowerSeed() && !queries.pluginOverride();
      TargetView chosen = null;
      int smallestDx = NO_SCORE;
      for (TargetView candidate : candidates) {
        boolean sameLane = candidate.getEntity().getLane() == unitLane;
        if (restrictToLane && !sameLane) {
          if (elapsedMs < LANE_RESTRICTION_TIME_MS) {
            continue;
          }
          if (candidates.size() == 1) {
            continue;
          }
        }
        int dx = Math.abs(candidate.x() - unitX);
        if (dx < smallestDx) {
          chosen = candidate;
          smallestDx = dx;
        }
      }
      if (chosen != null) {
        int distance = approxDistance(chosen.x() - unitX, chosen.y() - unitY);
        int score = distance * distance;
        if (score < threshold && validate.test(chosen)) {
          current = chosen;
        }
      }
      return current;
    }
    boolean requireMatchingLane =
        rules.princessTowersAlwaysDefault() && candidates.size() < expected;
    return rankCandidates(
            unitX,
            unitY,
            unitLane,
            arenaHeightCells,
            candidates,
            current,
            threshold,
            rules.useLaneId(),
            requireMatchingLane,
            validate)
        .selected();
  }

  /**
   * The seed's own squared distance to the unit, the true one rather than the approximation the
   * candidates are scored by, which is the score every candidate has to beat; it saturates at the
   * largest int as the guarded sum of squares does.
   */
  private static int seedDistanceScore(TargetView seed, int unitX, int unitY) {
    if (seed == null) {
      return NO_SCORE;
    }
    return FixedMath.squaredDistance(unitX, unitY, seed.x(), seed.y());
  }

  /** True when the candidate's configuration is the row the mode excludes. */
  private static boolean configKeyMatches(TargetView candidate, String key) {
    return candidate.getConfig() != null
        && candidate.getConfig().configKey() != null
        && candidate.getConfig().configKey().equals(key);
  }
}
