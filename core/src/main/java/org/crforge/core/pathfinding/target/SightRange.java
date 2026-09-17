package org.crforge.core.pathfinding.target;

/**
 * How far a unit notices candidates.
 *
 * <p>The answer is the owner's SightRange column, which the active attack sequence step may
 * override. The step lookup has one quirk that is part of the behaviour: with a sequence of two or
 * more steps the index is read without checking for "no step active", so a component between steps
 * reads the step id stored before the start of the sequence rather than skipping the override.
 */
public final class SightRange {

  /** Shortest attack sequence that can override the sight range. */
  private static final int OVERRIDING_SEQUENCE_LENGTH = 2;

  private SightRange() {
    // Utility class
  }

  /** The owner's current sight range, in game units. */
  public static int sightRange(TargetingState t) {
    TargetingConfig cfg = t.getConfig();
    int range = cfg.sightRange();
    if (cfg.attackSequenceLength() >= OVERRIDING_SEQUENCE_LENGTH) {
      AttackSequenceEntry entry = cfg.entry(cfg.stepId(t.getAttackSequenceIndex()));
      if (entry != null && entry.sightRangeOverride() != AttackSequenceEntry.NO_OVERRIDE) {
        range = entry.sightRangeOverride();
      }
    }
    return range;
  }
}
