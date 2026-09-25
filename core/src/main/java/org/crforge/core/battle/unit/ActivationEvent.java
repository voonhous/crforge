package org.crforge.core.battle.unit;

/**
 * One step of a king tower's activation, as an observer is told it.
 *
 * @param kind which step
 * @param phase the pending pass an action started in; 0 for a step that is not a start
 * @param damaged for the condition: whether the king had lost hit points
 * @param towerDestroyed for the condition: whether its side had lost a princess tower
 */
public record ActivationEvent(Kind kind, int phase, boolean damaged, boolean towerDestroyed) {

  /** The steps of an activation, in the order they happen. */
  public enum Kind {
    /** The wait saw its condition hold and finished. */
    CONDITION,
    /** The activating run started. */
    ACTIVATING_STARTED,
    /** The activation's effect was shown. */
    EFFECT,
    /** The activating run finished. */
    ACTIVATING_FINISHED,
    /** The activating run was removed, and its tag with it. */
    ACTIVATING_REMOVED
  }

  /** A step with nothing to say beyond its kind and, for a start, its phase. */
  public static ActivationEvent of(Kind kind, int phase) {
    return new ActivationEvent(kind, phase, false, false);
  }
}
