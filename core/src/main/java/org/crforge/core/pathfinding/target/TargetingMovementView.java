package org.crforge.core.pathfinding.target;

import lombok.Getter;
import lombok.Setter;

/**
 * The handful of movement-component answers the targeting visit reads, and the one field it writes.
 *
 * <p>The movement component itself belongs to another pass. Only these five values cross the
 * boundary, so they are carried here rather than by a dependency between the two passes.
 */
@Getter
@Setter
public class TargetingMovementView {

  /** Pushback still travelling; while it is non-zero the unit does not attack. */
  private int pushbackInFlight;

  /** Countdown that blocks movement; while it runs the unit does not attack either. */
  private int blockCountdownMs;

  /** Pushback caused by the unit's own attack; while it is non-zero the attack does not repeat. */
  private int attackPushback;

  /** Remaining dash time; a dash that reaches a building is cut short by setting this to zero. */
  private int dashTimeMs;
}
