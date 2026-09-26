package org.crforge.core.pathfinding.move;

/**
 * What a pushback request asks of the entity it would push, beyond the entity's own position,
 * flags, state and movement component.
 */
public interface PushbackQueries {

  /** True when the entity's row ignores pushback. */
  boolean ignoresPushback();

  /** True when one of the entity's buffs refuses pushback. No buff is modelled yet: false. */
  default boolean buffRefusesPushback() {
    return false;
  }

  /** True while the entity is hidden. Nothing hides yet: false. */
  default boolean hidden() {
    return false;
  }

  /**
   * The wind-up of a dash the entity's targeting component is running, in milliseconds, or 0 or
   * less for none. No ported unit dashes yet: 0.
   */
  default int dashWindupMs() {
    return 0;
  }

  /** Resumes the entity out of a dash wind-up and resets its dash chain. */
  default void resumeDashWindup() {}

  /** The longest pushback the battle allows, in game units. */
  default int maxPushbackLength() {
    return PushbackRequest.MAX_PUSHBACK_LENGTH;
  }
}
