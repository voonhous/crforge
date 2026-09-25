package org.crforge.core.battle.action;

import java.util.function.BooleanSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts until its condition holds: each run-pass step evaluates the condition, and
 * on the step it is true the action schedules its activation action and finishes.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the condition evaluated once per run-pass step, the activation action scheduled"
            + " and the run finished on the step it holds, and the tags the run sets. The condition"
            + " is supplied as code rather than an expression.")
public final class WaitToActivate implements BattleAction {

  private final String name;
  private final BooleanSupplier condition;
  private final BattleAction onActivateAction;
  private final long tags;

  /**
   * @param name the row's name
   * @param condition what ends the wait
   * @param onActivateAction scheduled, with no delay, when the wait ends
   * @param tags the tags the wait sets while it is listed
   */
  public WaitToActivate(
      String name, BooleanSupplier condition, BattleAction onActivateAction, long tags) {
    this.name = name;
    this.condition = condition;
    this.onActivateAction = onActivateAction;
    this.tags = tags;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new ActionInstance(this, tags) {
      @Override
      protected void update(ActionHolder h) {
        if (condition.getAsBoolean()) {
          h.schedule(onActivateAction, 0);
          finish();
        }
      }
    };
  }
}
