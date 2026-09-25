package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts a fixed time and sets its tags for as long as it is listed.
 *
 * <p>The run keeps its time in thousandths of a millisecond and compares before it advances: a step
 * finishes the run once the counter has reached the duration, and otherwise adds one tick's 50 ms.
 * A 100 ms run therefore takes three steps.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the counter in thousandths of a millisecond, compared with the duration before"
            + " it is advanced by one tick, and the tags the run sets. Not modelled: a duration"
            + " that follows the entity's hit speed, and the action run at each step's start.")
public final class WithDuration implements BattleAction {

  /** One tick's advance of the counter: 50 ms in thousandths. */
  private static final int STEP = 50_000;

  private final String name;
  private final int durationMs;
  private final long tags;
  private final BattleAction nextAction;

  /**
   * @param name the row's name
   * @param durationMs how long the run lasts
   * @param tags the tags the run sets while it is listed
   * @param nextAction the action scheduled alongside this one, or null
   */
  public WithDuration(String name, int durationMs, long tags, BattleAction nextAction) {
    this.name = name;
    this.durationMs = durationMs;
    this.tags = tags;
    this.nextAction = nextAction;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public BattleAction nextAction() {
    return nextAction;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new ActionInstance(this, tags) {
      private long counter;

      @Override
      protected void update(ActionHolder h) {
        if (counter >= (long) durationMs * 1000) {
          finish();
        } else {
          counter += STEP;
        }
      }
    };
  }
}
