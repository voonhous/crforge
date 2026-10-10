/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that hands its action to what rides its owner: when it starts it schedules ActionToRun
 * on the holder of each character attached to the owner, in the order the owner made them, each
 * built for the rider it runs on, with the action's own cause as their cause and with no context.
 * An owner that carries no rider schedules nothing; a row without an action does nothing.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: the perform walks the owner's rider list in its order and schedules the action"
            + " on each rider's holder with the run's cause, its own delay, not at once and"
            + " without the context; the action's own at-once slot answers false for a group,"
            + " the only class the shipped rows hand over. Held by BattleRunOnAttachedTest.")
public final class RunOnAttached extends RowAction {

  /** The name of the row run on each rider, or null for none. */
  private final String actionToRun;

  /**
   * @param row the row's shared columns
   * @param actionToRun the name of the row scheduled on each rider, or null for none
   */
  public RunOnAttached(ActionRow row, String actionToRun) {
    super(row);
    this.actionToRun = actionToRun;
  }

  /** The name of the row run on each rider, or null for none. */
  public String actionToRun() {
    return actionToRun;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (actionToRun != null) {
      holder.getOwner().runOnAttached(this, actionToRun, instigator);
    }
    return null;
  }
}
