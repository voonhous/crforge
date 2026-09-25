package org.crforge.core.battle.action;

/**
 * One action an entity can schedule: what starting it does, and whether it lasts.
 *
 * <p>An action that lasts returns an instance when it starts, which the holder's run pass then
 * steps once per tick until it finishes; one that does not returns null and is done once started.
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

  /** The action scheduled alongside this one when it is scheduled, or null for none. */
  default BattleAction nextAction() {
    return null;
  }

  /**
   * Starts the action on its holder.
   *
   * @return the running instance of an action that lasts, or null for one that is done
   */
  ActionInstance start(ActionHolder holder);
}
