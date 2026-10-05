package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that, when it starts, schedules its true branch or its false branch by its condition,
 * whose value defaults to true; a branch the row leaves out schedules nothing. The branch is
 * scheduled with its own delay, with the cause and the context the start carried.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases: both branches, the default of true, a missing"
            + " branch. The branch scheduled with the start's cause and context, read from the"
            + " newer build's perform; held by BattleRunOnResolvedTest.")
public final class Filter extends RowAction {

  private final IntSupplier condition;
  private final BattleAction onTrue;
  private final BattleAction onFalse;

  /**
   * @param row the row's shared columns
   * @param condition the condition, or null for true
   * @param onTrue scheduled when it holds, or null
   * @param onFalse scheduled when it does not, or null
   */
  public Filter(ActionRow row, IntSupplier condition, BattleAction onTrue, BattleAction onFalse) {
    super(row);
    this.condition = condition;
    this.onTrue = onTrue;
    this.onFalse = onFalse;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return start(holder, instigator, null);
  }

  /** The branch carries the cause and the context the start carried. */
  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    boolean holds = condition == null || condition.getAsInt() != 0;
    BattleAction branch = holds ? onTrue : onFalse;
    if (branch != null) {
      holder.schedule(branch, ActionHolder.OWN_DELAY, false, instigator, context);
    }
    return null;
  }
}
