package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that turns the direction round: when it starts it schedules its action on the holder of
 * the entity that caused it, with its own owner as the cause of that one. Without a cause it
 * schedules nothing.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases: the action on the cause's holder, the owner as"
            + " its cause, nothing without a cause.")
public final class RunOnInstigator extends RowAction {

  private final BattleAction action;

  /**
   * @param row the row's shared columns
   * @param action the action it schedules on the cause
   */
  public RunOnInstigator(ActionRow row, BattleAction action) {
    super(row);
    this.action = action;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator != null && action != null) {
      instigator.schedule(action, ActionHolder.OWN_DELAY, false, holder);
    }
    return null;
  }
}
