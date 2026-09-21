package org.crforge.core.pathfinding.target;

/**
 * One step of a unit's attack sequence, as the range and sight helpers read it.
 *
 * <p>A step may override three of the owner's columns. Each override is disabled by the value -1,
 * in which case the owner's own column is used.
 *
 * @param rangeOverride attack range of this step, or -1 to keep the owner's Range
 * @param minimumRangeOverride minimum range of this step, or -1 to keep the owner's MinimumRange
 * @param sightRangeOverride sight range of this step, or -1 to keep the owner's SightRange
 */
public record AttackSequenceEntry(
    int rangeOverride, int minimumRangeOverride, int sightRangeOverride) {

  /** Value that disables an override. */
  public static final int NO_OVERRIDE = -1;

  /** A step that overrides nothing. */
  public static AttackSequenceEntry none() {
    return new AttackSequenceEntry(NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE);
  }
}
