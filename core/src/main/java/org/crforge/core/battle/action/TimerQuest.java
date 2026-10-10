/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that keeps a run counting toward its intervals, which a bar shows: each step of the run
 * adds 50 to its progress, or for a row that follows the hit speed what its owner's buffs as they
 * stand on that step make of 50 (65 under Rage, 0 under a stun), and when the progress reaches the
 * interval it schedules its action on its owner, the owner as its cause, the row's own delay and
 * not asked to start at once, and counts one interval done. While intervals are left the interval
 * is taken off the progress; once as many are done as the row allows, the run stays listed and does
 * nothing more. It never ends by itself: only its stop gate or its owner leaving ends it.
 *
 * <p>The progress starts at the row's start value, never below 0. A row with one interval uses it
 * every time; a row with several takes the one at the count done, the last once past them. A row
 * with no intervals counts to 1000.
 *
 * <p>A row with a start delay spends its first steps taking 50 off the delay, counting nothing,
 * while the delay is 1 or more. A row with an upgrade gate asks it on each counting step, on the
 * owner, before anything is added: while it holds, the step adds the row's upgrade amount instead
 * of 50 (one amount for every interval, or the one at the count done, the last once past them; none
 * listed is 0), and a negative amount fills the progress to the interval at once. At most one
 * interval is counted out per step.
 *
 * <p>The hero Mini Pekka's ability level timer is the same run: its row adds the labels and values
 * of the ability button, which only the button shows.
 *
 * <p>Its bar's names, files, inversion and the interval the other player is shown are read only by
 * what the bar shows.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: the start value floored at 0, 50 a step, the action scheduled on the owner as"
            + " the progress reaches the interval, the interval taken off while intervals are"
            + " left, idle after the last, and no end of its own; held by hero_goblins. The start"
            + " delay counted down 50 a step before any counting, and the upgrade gate asked on"
            + " the owner each counting step, adding the upgrade amount instead of 50 or filling"
            + " the progress for a negative one; held by hero_mini_pekka. A counting step under a"
            + " hit speed buff adds what the buffs make of 50, asked each step;"
            + " random_battle16_s0046 reaches it and agrees, though no recorded battle yet moves"
            + " on the scaling. Refused: the action after the last interval and the segmented"
            + " bar, as columns.")
public final class TimerQuest extends RowAction {

  /** What a row with no intervals counts to. */
  public static final int DEFAULT_INTERVAL_MS = 1000;

  /** What one step adds to the progress with no hit speed buff, and what a buff scales. */
  private static final int STEP_MS = 50;

  private final List<Integer> intervalsMs;
  private final int startAtMs;
  private final int startDelayMs;
  private final int maxResets;
  private final IntSupplier upgradeIf;
  private final List<Integer> upgradeAmountsMs;
  private final BattleAction onIntervalReached;
  private final IntUnaryOperator hitSpeed;

  /**
   * @param row the row's shared columns
   * @param intervalsMs the intervals, each in milliseconds; empty counts to {@link
   *     #DEFAULT_INTERVAL_MS}
   * @param startAtMs the progress it starts at
   * @param startDelayMs the delay its first steps count down before it counts, 0 for none
   * @param maxResets how many intervals it counts out; with several intervals, no more than there
   *     are
   * @param upgradeIf the upgrade gate, asked on the owner each counting step, or null for none
   * @param upgradeAmountsMs what a step adds while the gate holds, each in milliseconds; empty adds
   *     0; with several, no fewer than the intervals counted out
   * @param onIntervalReached scheduled as each interval is reached, or null
   * @param hitSpeed what a counting step without an upgrade adds of a base step: the owner's hit
   *     speed scaling for a row that follows the hit speed, else the step unchanged
   */
  public TimerQuest(
      ActionRow row,
      List<Integer> intervalsMs,
      int startAtMs,
      int startDelayMs,
      int maxResets,
      IntSupplier upgradeIf,
      List<Integer> upgradeAmountsMs,
      BattleAction onIntervalReached,
      IntUnaryOperator hitSpeed) {
    super(row);
    if (intervalsMs.size() > 1 && maxResets > intervalsMs.size()) {
      throw new UnsupportedOperationException(
          row.name() + " counts out more intervals than it lists, which reads past them");
    }
    if (upgradeAmountsMs.size() > 1 && maxResets > upgradeAmountsMs.size()) {
      throw new UnsupportedOperationException(
          row.name() + " counts out more intervals than it lists upgrades, which reads past them");
    }
    this.intervalsMs =
        intervalsMs.isEmpty() ? List.of(DEFAULT_INTERVAL_MS) : List.copyOf(intervalsMs);
    this.startAtMs = startAtMs;
    this.startDelayMs = startDelayMs;
    this.maxResets = maxResets;
    this.upgradeIf = upgradeIf;
    // The game's loader lists one amount of 0 for a row that lists none.
    this.upgradeAmountsMs = upgradeAmountsMs.isEmpty() ? List.of(0) : List.copyOf(upgradeAmountsMs);
    this.onIntervalReached = onIntervalReached;
    this.hitSpeed = hitSpeed;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  private final class Run extends ActionInstance implements CountingRun {
    private int progress = Math.max(startAtMs, 0);
    private int delay = startDelayMs;
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
      // The start delay: while 1 or more is left, a step only takes 50 off it.
      if (delay >= 1) {
        delay -= STEP_MS;
        return;
      }
      if (done >= maxResets) {
        return;
      }
      // Asked on every counting step, before the interval is chosen.
      boolean upgrade = upgradeIf != null && upgradeIf.getAsInt() != 0;
      int interval = pick(intervalsMs);
      if (upgrade) {
        int amount = pick(upgradeAmountsMs);
        progress = amount < 0 ? interval : progress + amount;
      } else {
        // The buffs as they stand on this step; the start delay above is never scaled.
        progress += hitSpeed.applyAsInt(STEP_MS);
      }
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

    /** The entry of a list for this interval: the only one, else the one at the count done. */
    private int pick(List<Integer> values) {
      return values.size() == 1 ? values.get(0) : values.get(Math.min(done, maxResets - 1));
    }
  }
}
