package org.crforge.core.pathfinding.target;

import org.crforge.core.pathfinding.GridEntity;

/**
 * The bits of {@link GridEntity#getFlags()} the targeting pass reads.
 *
 * <p>The flag word is 64 bits wide, so every constant here is a {@code long}. Only the bits the
 * targeting code itself tests are listed; the movement and push passes read others from the same
 * word.
 */
public final class TargetingFlags {

  private TargetingFlags() {
    // Constants holder
  }

  /** The entity is running a dash. The visit raises it on the entity's pending flags in state 3. */
  public static final long DASHING = 1L << 2;

  /** The entity may not start a dash. */
  public static final long NO_DASH = 1L << 8;

  /** The entity may not attack at all. */
  public static final long NO_ATTACK = 1L << 10;

  /** The entity keeps the reference it has and the selector returns without choosing. */
  public static final long LOCK_TARGET = 1L << 12;

  /** The entity may not use its special attack. */
  public static final long NO_SPECIAL_ATTACK = 1L << 35;

  /** Nothing may take this entity as a target. */
  public static final long UNTARGETABLE = 1L << 52;
}
