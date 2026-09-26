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

  /** True when a queued run is dropped as the entity that caused it leaves the battle. */
  default boolean abortIfInstigatorDies() {
    return true;
  }

  /** The queue gate: a due entry stays queued while its value is non-zero. Null for no gate. */
  default IntSupplier pausedIf() {
    return null;
  }

  /**
   * Runs every time the action is scheduled, whether it starts at once or is queued. The actions
   * that schedule other actions when they are scheduled do their work here.
   *
   * @param holder the holder it was scheduled on
   * @param delayMs the delay it was scheduled with, the row's own already put in for none
   * @param immediate whether the schedule asked a due action to start at once
   * @param instigator the holder of the entity that caused it, or null for none
   */
  default void scheduled(
      ActionHolder holder, int delayMs, boolean immediate, ActionHolder instigator) {}

  /**
   * Starts the action on its holder: what the action does, and its run if it lasts.
   *
   * @return the running instance of an action that lasts, or null for one that is done
   */
  ActionInstance start(ActionHolder holder);

  /**
   * Starts the action on its holder, knowing what caused it. By default the cause does not matter.
   *
   * @param holder the holder it starts on
   * @param instigator the holder of the entity that caused it, or null for none
   * @return the running instance of an action that lasts, or null for one that is done
   */
  default ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return start(holder);
  }
}
