package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts and holds a state, off at first. Each step its timer moves on 50 ms while
 * the condition disagrees with the state and goes back to zero the moment it agrees, so a flicker
 * does not add up. When the timer reaches the activation time the state turns on and the activated
 * action is scheduled; when it reaches the deactivation time the state turns off and the
 * deactivated action is scheduled. It never ends on its own.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the state off at first, the timer moving only"
            + " while the condition disagrees and reset when it agrees, both turns and their"
            + " actions, and that it never ends. Supplied: a row without a condition reads as"
            + " false, which no recorded case reaches.")
public final class FlipFlop extends RowAction {

  /** Milliseconds one step moves the timer on. */
  private static final int STEP_MS = 50;

  private final IntSupplier condition;
  private final int activationMs;
  private final int deactivationMs;
  private final BattleAction onActivated;
  private final BattleAction onDeactivated;

  /**
   * @param row the row's shared columns
   * @param condition the condition, or null for false
   * @param activationMs how long it must hold before the state turns on
   * @param deactivationMs how long it must fail before the state turns off
   * @param onActivated scheduled when the state turns on, or null
   * @param onDeactivated scheduled when the state turns off, or null
   */
  public FlipFlop(
      ActionRow row,
      IntSupplier condition,
      int activationMs,
      int deactivationMs,
      BattleAction onActivated,
      BattleAction onDeactivated) {
    super(row);
    this.condition = condition;
    this.activationMs = activationMs;
    this.deactivationMs = deactivationMs;
    this.onActivated = onActivated;
    this.onDeactivated = onDeactivated;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  /** One run: the state and its timer. */
  public final class Run extends ActionInstance {

    /** True while the state is on. */
    @Getter private boolean active;

    /** How long the condition has disagreed with the state, in milliseconds. */
    @Getter private int timerMs;

    private Run() {
      super(FlipFlop.this);
    }

    @Override
    protected void update(ActionHolder holder) {
      boolean holds = condition != null && condition.getAsInt() != 0;
      if (holds == active) {
        timerMs = 0;
        return;
      }
      timerMs += STEP_MS;
      if (timerMs >= (active ? deactivationMs : activationMs)) {
        active = !active;
        timerMs = 0;
        BattleAction turned = active ? onActivated : onDeactivated;
        if (turned != null) {
          holder.schedule(turned, ActionHolder.OWN_DELAY);
        }
      }
    }
  }
}
