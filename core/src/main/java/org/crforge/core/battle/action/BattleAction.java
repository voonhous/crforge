package org.crforge.core.battle.action;

import java.util.function.IntSupplier;

/**
 * One action an entity can schedule: the columns every action row shares, what starting it does,
 * and whether it lasts.
 *
 * <p>An action that lasts returns an instance when it starts, which the holder's run pass then
 * steps once per tick until it finishes; one that does not returns null and is done once started.
 *
 * <p>The three gates are the row's expression columns, answered with the value the expression
 * evaluates to; null stands for a column the row leaves out.
 */
public interface BattleAction {

  /** A phase of 0 lets whichever pending pass reaches the action take it. */
  int ANY_PHASE = 0;

  /** The action's name, as the data names the row. */
  String name();

  /**
   * The pending pass that starts the action: 1, 2 or 3, or {@link #ANY_PHASE}. Rows without the
   * column take the first pass that reaches them.
   */
  default int phase() {
    return ANY_PHASE;
  }

  /** The row's own delay in milliseconds, which a schedule without a delay of its own uses. */
  default int delayMs() {
    return 0;
  }

  /** True when a second start re-triggers the run already listed instead of adding one. */
  default boolean singleton() {
    return false;
  }

  /** The action chained to this one, or null for none. */
  default BattleAction nextAction() {
    return null;
  }

  /**
   * True when the next action is scheduled once this one has run; false when it is scheduled
   * alongside this one.
   */
  default boolean nextActionWait() {
    return false;
  }

  /** The tags a run of the action sets on its entity while it is listed. */
  default long tags() {
    return 0;
  }

  /** The start gate: a run whose value is 0 does nothing. Null for no gate. */
  default IntSupplier executeIf() {
    return null;
  }

  /** The run gate: a run is stopped once its value is non-zero. Null for no gate. */
  default IntSupplier forceStopIf() {
    return null;
  }

  /** The queue gate: a due entry stays queued while its value is non-zero. Null for no gate. */
  default IntSupplier pausedIf() {
    return null;
  }

  /**
   * Runs every time the action is scheduled, whether it starts at once or is queued. The actions
   * that schedule other actions do their work here.
   *
   * @param holder the holder it was scheduled on
   * @param delayMs the delay it was scheduled with
   */
  default void scheduled(ActionHolder holder, int delayMs) {}

  /**
   * Starts the action on its holder: what the action does, and its run if it lasts.
   *
   * @return the running instance of an action that lasts, or null for one that is done
   */
  ActionInstance start(ActionHolder holder);
}
