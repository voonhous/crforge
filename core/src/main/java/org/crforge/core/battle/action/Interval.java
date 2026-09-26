package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts and fires its action every interval. The counter starts at its start value
 * and each step takes off half the rate percentage - 50 ms at the usual 100 - unless the owner
 * carries the pause tag, when it does not move at all. At zero or below it adds the interval to
 * what is left, so it does not drift, and schedules the action on its owner. It never ends on its
 * own.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the start value, the half-rate step, the reload"
            + " by adding the interval, the action scheduled on the owner, the pause tag and that"
            + " it never ends. Supplied: the rate percentage, which the hit speed, speed or spawn"
            + " speed would set, answers as given.")
public final class Interval extends RowAction {

  private final int intervalMs;
  private final int startCounterMs;
  private final BattleAction action;
  private final long pauseMask;
  private final LongSupplier ownerTags;
  private final IntSupplier ratePercent;

  /**
   * @param row the row's shared columns
   * @param intervalMs the time between two firings
   * @param startCounterMs the counter's start value
   * @param action what it fires, or null
   * @param pauseMask the tags that hold it still, 0 for none
   * @param ownerTags the owner's tags as they stand
   * @param ratePercent the rate its steps follow, 100 for the usual
   */
  public Interval(
      ActionRow row,
      int intervalMs,
      int startCounterMs,
      BattleAction action,
      long pauseMask,
      LongSupplier ownerTags,
      IntSupplier ratePercent) {
    super(row);
    this.intervalMs = intervalMs;
    this.startCounterMs = startCounterMs;
    this.action = action;
    this.pauseMask = pauseMask;
    this.ownerTags = ownerTags;
    this.ratePercent = ratePercent;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  private final class Run extends ActionInstance implements CountingRun {
    private int counter = startCounterMs;

    private Run() {
      super(Interval.this);
    }

    @Override
    public long counter() {
      return counter;
    }

    @Override
    protected void update(ActionHolder holder) {
      if ((ownerTags.getAsLong() & pauseMask) != 0) {
        return;
      }
      counter -= ratePercent.getAsInt() / 2;
      if (counter <= 0) {
        counter += intervalMs;
        if (action != null) {
          holder.schedule(action, ActionHolder.OWN_DELAY);
        }
      }
    }
  }
}
