package org.crforge.core.battle.action;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts until its condition holds: each run-pass step evaluates the condition, whose
 * value defaults to false, and on the step it is true the action schedules its activation action,
 * if it has one, and finishes.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the condition evaluated once per run-pass step with a default of false, the"
            + " activation action scheduled with its own delay and the run finished on the step it"
            + " holds, with or without an action, and the tags the run sets. Held by the recorded"
            + " cases and the king tower's runs; the king's condition is its row's expression.")
public final class WaitToActivate extends RowAction {

  private final IntSupplier condition;
  private final BattleAction onActivateAction;

  /**
   * @param row the row's shared columns
   * @param condition what ends the wait, or null for false
   * @param onActivateAction scheduled when the wait ends, or null
   */
  public WaitToActivate(ActionRow row, IntSupplier condition, BattleAction onActivateAction) {
    super(row);
    this.condition = condition;
    this.onActivateAction = onActivateAction;
  }

  /**
   * @param name the row's name
   * @param condition what ends the wait
   * @param onActivateAction scheduled, with its own delay, when the wait ends
   * @param tags the tags the wait sets while it is listed
   */
  public WaitToActivate(
      String name, BooleanSupplier condition, BattleAction onActivateAction, long tags) {
    this(
        ActionRow.builder().name(name).tags(tags).build(),
        () -> condition.getAsBoolean() ? 1 : 0,
        onActivateAction);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new ActionInstance(this) {
      @Override
      protected void update(ActionHolder h) {
        if (condition != null && condition.getAsInt() != 0) {
          if (onActivateAction != null) {
            h.schedule(onActivateAction, ActionHolder.OWN_DELAY);
          }
          finish();
        }
      }
    };
  }
}
