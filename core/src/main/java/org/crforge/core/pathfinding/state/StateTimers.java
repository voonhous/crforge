package org.crforge.core.pathfinding.state;

import lombok.Getter;
import lombok.Setter;
import org.crforge.core.pathfinding.GridEntity;

/**
 * The countdowns and flags the entity state visit owns that do not belong on {@link GridEntity}
 * itself.
 *
 * <p>Everything here is per entity and advances in milliseconds, 50 per tick at 20 ticks per
 * second. A plain ground unit leaves all of them at zero for its whole life; they matter to
 * dashing, morphing and ability-carrying cards.
 *
 * <p>This is a plain mutable holder with no behaviour, and is not thread safe.
 */
@Getter
@Setter
public final class StateTimers {

  /**
   * True while a spawned entity may not be targeted: a death spawn's children start with it, and
   * while it is set the entity refuses every character that asks to target it. It advances {@link
   * #spawnImmuneElapsedMs}.
   *
   * <p>This pair was first read as an attack-finish latch, because the limit that clears it is the
   * attack-finish time. The spawner is what sets it, and the validator is what reads it, so it is
   * the first-tick immunity of a spawned unit.
   */
  private boolean spawnImmune;

  /** Milliseconds the immunity has lasted; once past the attack-finish time it clears the flag. */
  private int spawnImmuneElapsedMs;

  /** Milliseconds of pending damage still to be applied, counted down 50 per visit. */
  private int pendingDamageDurationMs;

  /** Milliseconds of remaining immunity after a dash, reset while the entity is protected. */
  private int dashImmunityRemainingMs;

  /** Milliseconds left of a morph, counted down 50 per visit; at zero the entity stands. */
  private int morphCountdownMs;

  /** Visits left before an ability's warning fires. */
  private int abilityWarningCountdown;

  /** Visits left of an ability; at zero the entity stands. */
  private int abilityCountdown;

  /** True when an ability has been requested and is waiting to start. */
  private boolean abilityReady;

  /** Set when the entity has asked to be removed from play. */
  private boolean removalRequested;

  /**
   * True when the entity stops as soon as it reaches its own side's goal row. Its writers are not
   * documented, so the name describes only where it is read.
   */
  private boolean stopsAtGoalRow;

  /** True while the entity is attached to another, which holds its dash immunity topped up. */
  private boolean attached;

  /** The entity this one follows while it is removed from play, or null. */
  private GridEntity followTarget;
}
