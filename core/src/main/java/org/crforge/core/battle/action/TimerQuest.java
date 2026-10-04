package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that keeps a run counting toward its intervals, which a bar shows: each step of the run
 * adds 50 to its progress, and when the progress reaches the interval it schedules its action on
 * its owner, the owner as its cause, the row's own delay and not asked to start at once, and counts
 * one interval done. While intervals are left the interval is taken off the progress; once as many
 * are done as the row allows, the run stays listed and does nothing more. It never ends by itself:
 * only its stop gate or its owner leaving ends it.
 *
 * <p>The progress starts at the row's start value, never below 0. A row with one interval uses it
 * every time; a row with several takes the one at the count done, the last once past them. A row
 * with no intervals counts to 1000.
 *
 * <p>Its bar's names, files, inversion and the interval the other player is shown are read only by
 * what the bar shows. Refused rather than guessed: a step taken while a buff changes the owner's
 * hit speed, which the progress follows by a scaling not established.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: the start value floored at 0, 50 a step, the action scheduled on the owner as"
            + " the progress reaches the interval, the interval taken off while intervals are"
            + " left, idle after the last, and no end of its own; held by hero_goblins. Refused: a"
            + " step under a hit speed buff, and the start delay, the upgrade, the action after the"
            + " last interval and the segmented bar, as columns.")
public final class TimerQuest extends RowAction {

  /** What a row with no intervals counts to. */
  public static final int DEFAULT_INTERVAL_MS = 1000;

  /** What one step adds to the progress with no hit speed buff. */
  private static final int STEP_MS = 50;

  private final List<Integer> intervalsMs;
  private final int startAtMs;
  private final int maxResets;
  private final BattleAction onIntervalReached;
  private final BooleanSupplier hitSpeedBuffed;

  /**
   * @param row the row's shared columns
   * @param intervalsMs the intervals, each in milliseconds; empty counts to {@link
   *     #DEFAULT_INTERVAL_MS}
   * @param startAtMs the progress it starts at
   * @param maxResets how many intervals it counts out; with several intervals, no more than there
   *     are
   * @param onIntervalReached scheduled as each interval is reached, or null
   * @param hitSpeedBuffed whether a buff changes the owner's hit speed as it stands
   */
  public TimerQuest(
      ActionRow row,
      List<Integer> intervalsMs,
      int startAtMs,
      int maxResets,
      BattleAction onIntervalReached,
      BooleanSupplier hitSpeedBuffed) {
    super(row);
    if (intervalsMs.size() > 1 && maxResets > intervalsMs.size()) {
      throw new UnsupportedOperationException(
          row.name() + " counts out more intervals than it lists, which reads past them");
    }
    this.intervalsMs =
        intervalsMs.isEmpty() ? List.of(DEFAULT_INTERVAL_MS) : List.copyOf(intervalsMs);
    this.startAtMs = startAtMs;
    this.maxResets = maxResets;
    this.onIntervalReached = onIntervalReached;
    this.hitSpeedBuffed = hitSpeedBuffed;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  private final class Run extends ActionInstance implements CountingRun {
    private int progress = Math.max(startAtMs, 0);
    private int done;

    private Run() {
      super(TimerQuest.this);
    }

    @Override
    public long counter() {
      return progress;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (done >= maxResets) {
        return;
      }
      int interval =
          intervalsMs.size() == 1
              ? intervalsMs.get(0)
              : intervalsMs.get(Math.min(done, maxResets - 1));
      if (hitSpeedBuffed.getAsBoolean()) {
        throw new UnsupportedOperationException(
            name() + " steps under a buff on its owner's hit speed, whose scaling is not modelled");
      }
      progress += STEP_MS;
      if (progress >= interval) {
        if (onIntervalReached != null) {
          holder.schedule(onIntervalReached, ActionHolder.OWN_DELAY, false, holder);
        }
        done++;
        if (done < maxResets) {
          progress -= interval;
        }
      }
    }
  }
}
