package org.crforge.core.pathfinding.target;

import java.util.List;

/**
 * What the candidate selector needs from outside the targeting pass: the candidate list, the
 * validator's answers and the default target.
 *
 * <p>The two candidate methods are called at the point in the selection where the original asks for
 * them, so an implementation that walks the spatial index does its work in the same order. The
 * implementation is responsible for returning its result list to the index when the selector calls
 * {@link #releaseCandidates(List)}.
 */
public interface SelectionQueries {

  /**
   * The entities within {@code radius} of the point, as the selector's own circle query returns
   * them: in bucket order with king towers last.
   */
  List<TargetView> candidates(int x, int y, int radius);

  /** Every entity the index holds, used instead of a circle query when the selector is told to. */
  List<TargetView> allCandidates();

  /** Returns the candidate list to the index. */
  default void releaseCandidates(List<TargetView> candidates) {
    // Nothing to return by default.
  }

  /** The validator's answer for one candidate in one mode. */
  boolean validate(TargetView candidate, int mode);

  /** True when the candidate carries the buff that lowers its priority. */
  default boolean carriesDeprioritizingBuff(TargetView candidate) {
    return false;
  }

  /** The tower the unit falls back to when no candidate is worth attacking. */
  TargetView defaultTarget();

  /**
   * True in a mode where a unit that can take nothing gives up its reference instead of keeping the
   * one it has.
   */
  default boolean touchdownMode() {
    return false;
  }
}
