/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.target;

import lombok.Builder;

/**
 * One step of a unit's attack sequence, as the range and sight helpers and the attack-timer advance
 * read it.
 *
 * <p>A step may override five of the owner's columns. Each override is disabled by the value -1, in
 * which case the owner's own column is used. A step also carries its hit speed multiplier, in
 * percent; half of it is what the attack time advances by on each attack tick, so the default of
 * 100 gives the ordinary 50 ms step.
 *
 * @param rangeOverride attack range of this step, or -1 to keep the owner's Range
 * @param minimumRangeOverride minimum range of this step, or -1 to keep the owner's MinimumRange
 * @param sightRangeOverride sight range of this step, or -1 to keep the owner's SightRange
 * @param hitSpeedMultiplier percentage the attack time advances at during this step; 100 is the
 *     ordinary pace
 * @param multipleTargetsOverride how many targets a hit of this step reaches, or -1 to keep the
 *     owner's MultipleTargets
 * @param rememberMultipleTargets whether a hit of this step remembers the targets it reached: 1 or
 *     another non-zero value for yes, 0 for no, -1 to keep the owner's RememberMultipleTargets
 * @param attackStartDelay milliseconds the attack timer is held at 0 when an attack starts on this
 *     step; 0 for none
 */
@Builder(toBuilder = true)
public record AttackSequenceEntry(
    int rangeOverride,
    int minimumRangeOverride,
    int sightRangeOverride,
    int hitSpeedMultiplier,
    int multipleTargetsOverride,
    int rememberMultipleTargets,
    int attackStartDelay) {

  /** Value that disables an override. */
  public static final int NO_OVERRIDE = -1;

  /** The hit speed multiplier of a step that does not change the pace. */
  public static final int DEFAULT_HIT_SPEED_MULTIPLIER = 100;

  /**
   * A step that overrides only the three ranges and the pace: it keeps the owner's number of
   * targets and its remembering, and has no start delay.
   */
  public AttackSequenceEntry(
      int rangeOverride, int minimumRangeOverride, int sightRangeOverride, int hitSpeedMultiplier) {
    this(
        rangeOverride,
        minimumRangeOverride,
        sightRangeOverride,
        hitSpeedMultiplier,
        NO_OVERRIDE,
        NO_OVERRIDE,
        0);
  }

  /** A step that overrides nothing and keeps the ordinary pace. */
  public static AttackSequenceEntry none() {
    return new AttackSequenceEntry(
        NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE, DEFAULT_HIT_SPEED_MULTIPLIER);
  }
}
