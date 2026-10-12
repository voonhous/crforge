/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts and fires its action every interval. The counter starts at its start value
 * and each step takes off half the rate percentage - 50 ms at the usual 100 - unless the owner
 * carries the pause tag, when it does not move at all. At zero or below it adds the interval to
 * what is left, so it does not drift, and schedules the action on its owner, the owner as its
 * cause, carrying the context the run's start carried. Without MaxRepetitions it never ends on its
 * own.
 *
 * <p>With MaxRepetitions the run counts the times its counter ran out, the action fired or not (a
 * row without an action counts too). Each update, once the pause tag has let it through, a count
 * that has reached MaxRepetitions ends the run before its counter moves: the run that fired its
 * last time ends on its next update, and MaxRepetitions 0 ends it on its first, before it ever
 * fires. A paused run is not ended while the pause holds.
 *
 * <p>The rate is asked on every step, so a buff listed or taken off mid-interval changes only the
 * steps from then on. A rate below 1 - a stun's - moves nothing and fires nothing on that step.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the start value, the half-rate step, the reload"
            + " by adding the interval, the action scheduled on the owner, the pause tag and that"
            + " it never ends; a pause tag of several names, any one of which holds it, and a"
            + " singleton run re-triggered with nothing restarted, held by the reference battles"
            + " evo_firespirithut_vs_giant and evo_cannon_vs_giant. The rate percentage answers"
            + " as given, asked each step, a rate below 1 moving nothing: the row builder gives a"
            + " row that follows the hit speed what its owner's buffs make of 100 (held by"
            + " evo_electrogiant up to its first pulse's hit, and by a recorded evolved Electro"
            + " Giant under Rage whose second and third pulses come 7 and 28 ticks early), else a"
            + " row affected by the spawn speed its owner's spawn rate, which no run holds with a"
            + " buff, else 100. The action carries the context the run's start carried, held by"
            + " ActionContextTest. MaxRepetitions (the count of times the counter ran out, checked"
            + " after the pause tag and before the counter moves, ending the run on the update after"
            + " its last firing) is held by BattleIntervalRepetitionsTest only: the one row of the"
            + " data that sets it restarts a presentation chain, so no recorded battle tells it"
            + " from a run that never ends.")
public final class Interval extends RowAction {

  /** The rate of a row that follows no speed, and the hit speed a row that follows it scales. */
  public static final int USUAL_RATE = 100;

  /** The MaxRepetitions of a row that leaves the column out: the run never ends on its own. */
  public static final int NO_LIMIT = -1;

  private final int intervalMs;
  private final int startCounterMs;
  private final BattleAction action;
  private final long pauseMask;
  private final LongSupplier ownerTags;
  private final IntSupplier ratePercent;
  private final int maxRepetitions;

  /**
   * An interval without MaxRepetitions, which never ends on its own.
   *
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
    this(row, intervalMs, startCounterMs, action, pauseMask, ownerTags, ratePercent, NO_LIMIT);
  }

  /**
   * @param row the row's shared columns
   * @param intervalMs the time between two firings
   * @param startCounterMs the counter's start value
   * @param action what it fires, or null
   * @param pauseMask the tags that hold it still, 0 for none
   * @param ownerTags the owner's tags as they stand
   * @param ratePercent the rate its steps follow, 100 for the usual
   * @param maxRepetitions the count of firings after which the run ends, {@link #NO_LIMIT} for none
   */
  public Interval(
      ActionRow row,
      int intervalMs,
      int startCounterMs,
      BattleAction action,
      long pauseMask,
      LongSupplier ownerTags,
      IntSupplier ratePercent,
      int maxRepetitions) {
    super(row);
    this.intervalMs = intervalMs;
    this.startCounterMs = startCounterMs;
    this.action = action;
    this.pauseMask = pauseMask;
    this.ownerTags = ownerTags;
    this.ratePercent = ratePercent;
    this.maxRepetitions = maxRepetitions;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  private final class Run extends ActionInstance implements CountingRun {
    private int counter = startCounterMs;

    /** The times the counter ran out, fired or not. */
    private int repetitions;

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
      if (maxRepetitions != NO_LIMIT && repetitions >= maxRepetitions) {
        // The count has reached MaxRepetitions: the run ends before its counter moves.
        finish();
        return;
      }
      int rate = ratePercent.getAsInt();
      if (rate < 1) {
        // A stopped rate holds the counter where it is and fires nothing.
        return;
      }
      counter -= rate / 2;
      if (counter <= 0) {
        counter += intervalMs;
        if (action != null) {
          // The action carries the context the run's start carried.
          holder.schedule(action, ActionHolder.OWN_DELAY, false, holder, context());
        }
        repetitions++;
      }
    }
  }
}
