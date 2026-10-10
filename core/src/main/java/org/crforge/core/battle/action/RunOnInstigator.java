/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that turns the direction round: when it starts it schedules its action on the holder of
 * the entity that caused it, built for that entity, with its own owner as the cause of that one.
 * Without a cause, or without an action, it schedules nothing.
 *
 * <p>The action is built for the entity it runs on, so its expressions read that entity - its
 * point, its side, its radius - as the Ice Wizard hero's kill hook places its cube by the troop it
 * killed; the owner that hands it over is only its cause.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases: the action on the cause's holder, built for the"
            + " cause so its expressions read the cause, the owner as its cause, nothing without"
            + " a cause.")
public final class RunOnInstigator extends RowAction {

  /** The name of the row run on the cause, or null for none. */
  private final String actionToExecute;

  /**
   * @param row the row's shared columns
   * @param actionToExecute the name of the row it schedules on the cause, or null for none
   */
  public RunOnInstigator(ActionRow row, String actionToExecute) {
    super(row);
    this.actionToExecute = actionToExecute;
  }

  /** The name of the row run on the cause, or null for none. */
  public String actionToExecute() {
    return actionToExecute;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator != null && actionToExecute != null) {
      ActionOwner cause = instigator.getOwner();
      if (cause == null) {
        throw new UnsupportedOperationException(
            name() + " hands " + actionToExecute + " to a cause with no owner, not modelled");
      }
      cause.runFromInstigated(this, actionToExecute, holder);
    }
    return null;
  }
}
