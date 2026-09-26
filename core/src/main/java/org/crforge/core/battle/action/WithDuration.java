package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts a fixed time and sets its tags for as long as it is listed.
 *
 * <p>The run keeps its time in thousandths of a millisecond and compares before it advances: a step
 * finishes the run once the counter has reached the duration, and otherwise adds one tick's 50 ms,
 * or, for a row that follows the hit speed, the hit speed percentage times 500. A 100 ms run
 * therefore takes three steps. A singleton row's second start resets the counter when the row asks
 * for it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the counter in thousandths of a millisecond, compared with the duration before"
            + " it is advanced, the step of one tick or of the hit speed percentage times 500, the"
            + " reset a re-trigger makes when the row asks for it, and the tags the run sets. Held"
            + " by the recorded cases and the king tower's runs. Supplied: the hit speed"
            + " percentage answers as given.")
public final class WithDuration extends RowAction {

  /** One tick's advance of the counter: 50 ms in thousandths. */
  private static final int STEP = 50_000;

  /** Thousandths of a millisecond one hit speed percent advances a step by. */
  private static final int PER_PERCENT = 500;

  private final int durationMs;
  private final boolean affectedByHitSpeed;
  private final IntSupplier hitSpeedPercent;
  private final boolean resetTimerIfSingleton;

  /**
   * @param row the row's shared columns
   * @param durationMs how long the run lasts
   * @param affectedByHitSpeed true when a step follows the hit speed
   * @param hitSpeedPercent the owner's hit speed, in percent of the usual
   * @param resetTimerIfSingleton true when a singleton's second start resets the counter
   */
  public WithDuration(
      ActionRow row,
      int durationMs,
      boolean affectedByHitSpeed,
      IntSupplier hitSpeedPercent,
      boolean resetTimerIfSingleton) {
    super(row);
    this.durationMs = durationMs;
    this.affectedByHitSpeed = affectedByHitSpeed;
    this.hitSpeedPercent = hitSpeedPercent;
    this.resetTimerIfSingleton = resetTimerIfSingleton;
  }

  /**
   * @param name the row's name
   * @param durationMs how long the run lasts
   * @param tags the tags the run sets while it is listed
   * @param nextAction the action scheduled alongside this one, or null
   */
  public WithDuration(String name, int durationMs, long tags, BattleAction nextAction) {
    this(
        ActionRow.builder().name(name).tags(tags).nextAction(nextAction).build(),
        durationMs,
        false,
        () -> 100,
        false);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  private final class Run extends ActionInstance implements CountingRun {
    private long counter;

    private Run() {
      super(WithDuration.this);
    }

    @Override
    public long counter() {
      return counter;
    }

    @Override
    protected void update(ActionHolder h) {
      if (counter >= (long) durationMs * 1000) {
        finish();
      } else {
        counter += affectedByHitSpeed ? (long) hitSpeedPercent.getAsInt() * PER_PERCENT : STEP;
      }
    }

    @Override
    protected void retrigger(ActionHolder h) {
      if (resetTimerIfSingleton) {
        counter = 0;
      }
    }
  }
}
