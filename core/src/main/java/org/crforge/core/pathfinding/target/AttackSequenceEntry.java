package org.crforge.core.pathfinding.target;

/**
 * One step of a unit's attack sequence, as the range and sight helpers and the attack-timer advance
 * read it.
 *
 * <p>A step may override three of the owner's columns. Each override is disabled by the value -1,
 * in which case the owner's own column is used. A step also carries its hit speed multiplier, in
 * percent; half of it is what the attack time advances by on each attack tick, so the default of
 * 100 gives the ordinary 50 ms step.
 *
 * @param rangeOverride attack range of this step, or -1 to keep the owner's Range
 * @param minimumRangeOverride minimum range of this step, or -1 to keep the owner's MinimumRange
 * @param sightRangeOverride sight range of this step, or -1 to keep the owner's SightRange
 * @param hitSpeedMultiplier percentage the attack time advances at during this step; 100 is the
 *     ordinary pace
 */
public record AttackSequenceEntry(
    int rangeOverride, int minimumRangeOverride, int sightRangeOverride, int hitSpeedMultiplier) {

  /** Value that disables an override. */
  public static final int NO_OVERRIDE = -1;

  /** The hit speed multiplier of a step that does not change the pace. */
  public static final int DEFAULT_HIT_SPEED_MULTIPLIER = 100;

  /** A step that overrides nothing and keeps the ordinary pace. */
  public static AttackSequenceEntry none() {
    return new AttackSequenceEntry(
        NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE, DEFAULT_HIT_SPEED_MULTIPLIER);
  }
}
