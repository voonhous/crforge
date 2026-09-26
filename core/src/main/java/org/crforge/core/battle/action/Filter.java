package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that, when it starts, schedules its true branch or its false branch by its condition,
 * whose value defaults to true; a branch the row leaves out schedules nothing.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases: both branches, the default of true, a missing"
            + " branch.")
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
    boolean holds = condition == null || condition.getAsInt() != 0;
    BattleAction branch = holds ? onTrue : onFalse;
    if (branch != null) {
      holder.schedule(branch, ActionHolder.OWN_DELAY, false, instigator);
    }
    return null;
  }
}
