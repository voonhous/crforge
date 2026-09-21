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
 * <p>The selection starts from a seed - the opposing side's king tower - and ranks that side's
 * registered map objects, which are the three crown towers in the order they were placed: king,
 * left princess, right princess. Ordinary troops never enter that list. The king is part of the
 * candidates, which is what makes a unit deployed in the middle walk at the king tower until a
 * princess tower becomes closer in x.
 *
 * <p>Two rankings exist. With {@link PathfindingGlobals#LOGIC_XPOS_BASED_TOWER_TARGETING} the
 * candidate with the smallest difference in x wins and is then checked against the seed's own
 * distance; otherwise the candidates are ranked by an approximate squared distance with an optional
 * bonus for a matching lane. The published values select the x-position rule.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The published switches, the candidate filter, the smallest-offset rule and the"
            + " ranking agree with the reference, and the five reference walks hold them, two"
            + " of them through a switch from the king tower to a princess tower. Not settled,"
            + " and shared with the reference walks themselves: the value compared against 500"
            + " to skip a tower in another lane is fed the unit's attack range, where the"
            + " standard game appears to read how long the unit has been in its state, and the"
            + " seed threshold is the squared approximate distance where the standard game"
            + " appears to use the true one. The alternate seed, the goal mode and the"
            + " six-object branch are not held by any fixture.")
public final class DefaultTargetSelection {

  /** Score no candidate can reach, used as the starting threshold. */
  private static final int NO_SCORE = Integer.MAX_VALUE;

  /**
   * Attack range below which a unit is kept to the candidates of its own lane. A unit that reaches
   * further than half a cell may cross to another lane's tower.
   */
  private static final int LANE_RESTRICTION_RANGE = 500;

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
   * @param attackRange the unit's attack range including its own collision radius
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
      int attackRange,
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
          if (attackRange < LANE_RESTRICTION_RANGE) {
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
   * The seed's own approximate squared distance to the unit, which is the score every candidate has
   * to beat.
   */
  private static int seedDistanceScore(TargetView seed, int unitX, int unitY) {
    if (seed == null) {
      return NO_SCORE;
    }
    int distance = approxDistance(seed.x() - unitX, seed.y() - unitY);
    return distance * distance;
  }

  /** True when the candidate's configuration is the row the mode excludes. */
  private static boolean configKeyMatches(TargetView candidate, String key) {
    return candidate.getConfig() != null
        && candidate.getConfig().configKey() != null
        && candidate.getConfig().configKey().equals(key);
  }
}
