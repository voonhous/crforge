/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Rage Barbarian's ghost waiting out its rage: a run on the ghost that, once a buff it
 * swaps has reached it, runs its action on the ghost as soon as the ghost no longer carries the
 * buff it considers.
 *
 * <p><b>The swap.</b> While the run is listed, every buff applied to the ghost is first offered to
 * it, as to each of the ghost's runs from the last listed to the first, before any gate of the
 * apply: the buff to override is answered with the buff to consider, which is applied in its place
 * with the same time, level and source, and the run is armed. Any other buff passes unchanged. So a
 * ghost that ignores Rage still carries a dummy buff for as long as a Rage would last on it.
 *
 * <p><b>The start</b> raises NO_DAMAGE on the ghost for one step, as a code tag.
 *
 * <p><b>Each step</b> does nothing until the run is armed. Armed, it first renews the ghost's
 * spawned-child immunity, so no character may target it. Then the time to death is taken: the first
 * listed instance of the buff to consider, its remaining time plus the delay, or -1 with none. At
 * the portal time or more the run is not about to die (raising UNIT_CUSTOM_TAG_1, which only the
 * ghost's own effect rows read); below it the run is about to die, and getting there runs the
 * about-to-die action. Last, a ghost that carries the buff to consider resets the timer and the
 * step ends; one that does not adds 50 ms to the timer and, at the delay or more, runs the action
 * and finishes. Each action is scheduled on the ghost with the ghost as its cause.
 *
 * <p>Refused as the row is built (by its columns): a raged-back action, run as the time to death
 * comes back to the portal time, and ResetOnDelay cleared, which keeps the timer where it was while
 * the buff is carried; no shipped row sets either. Refused as it starts: an owner other than a
 * character, and a NO_DAMAGE start on an owner whose row does not set the tag already, which the
 * battle does not model as a code tag.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the swap offered before the apply's gates, last run to first, the"
            + " arming it does, the start's NO_DAMAGE, the immunity renewed each armed step, the"
            + " time to death from the first instance plus the delay, the about-to-die edge at"
            + " the portal time, the timer reset while the buff is carried and the action once"
            + " the timer reaches the delay. Held by evo_ragebarbarian_vs_musketeer. Refused: a"
            + " raged-back action and ResetOnDelay cleared, which no shipped row sets. Not"
            + " modelled: UNIT_CUSTOM_TAG_1, which only the ghost's own effect rows read.")
public final class LumberjackGhostWait extends RowAction {

  /** The step the timer advances by, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The time to death, and the first instance's remaining time, when the ghost has no instance. */
  private static final int NO_INSTANCE = -1;

  /**
   * The row's own columns, each with the loader's default when the row leaves it out.
   *
   * @param buffToConsider the buff whose loss runs the action, and which replaces the overridden
   *     one
   * @param buffOverride the buff that is replaced, and whose replacement arms the run
   * @param actionToExecute run on the ghost once it has gone the delay without the buff
   * @param delayMs how long the ghost must go without the buff, in milliseconds
   * @param portalTimerMs the time to death below which the ghost is about to die
   * @param onAboutToDie run on the ghost as it becomes about to die, or null
   */
  @Builder
  public record Columns(
      String buffToConsider,
      String buffOverride,
      BattleAction actionToExecute,
      int delayMs,
      int portalTimerMs,
      BattleAction onAboutToDie) {}

  /** What the run asks of the battle about the ghost it runs on. */
  public interface Host {

    /** The ghost's name, for a refusal. */
    String name();

    /** True when the ghost's own row sets the tag already. */
    boolean rowSetsNoDamage();

    /** Renews the ghost's spawned-child immunity: no character may target it for a while. */
    void renewSpawnImmunity();

    /**
     * The remaining time of the first listed instance of a buff on the ghost, or -1 with none.
     *
     * @param buff the buff's row name
     */
    int firstRemainingMs(String buff);

    /**
     * True when the ghost carries an instance of a buff.
     *
     * @param buff the buff's row name
     */
    boolean carries(String buff);
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns the row's own columns
   */
  public LumberjackGhostWait(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner owner = holder.getOwner();
    if (owner == null) {
      return null;
    }
    Host host = owner.lumberjackGhostHost(this);
    if (!host.rowSetsNoDamage()) {
      throw new UnsupportedOperationException(
          name()
              + " raises NO_DAMAGE on "
              + host.name()
              + ", whose row does not set it, as a code tag, which is not modelled");
    }
    return new Run(host);
  }

  /** One run on a ghost. */
  private final class Run extends ActionInstance {

    private final Host host;

    /** Milliseconds the ghost has gone without the buff to consider. */
    private int timerMs;

    /** True once a swap has armed the run. */
    private boolean armed;

    /** True while the time to death is below the portal time. */
    private boolean aboutToDie;

    private Run(Host host) {
      super(LumberjackGhostWait.this);
      this.host = host;
    }

    @Override
    protected String offeredBuff(String buff) {
      if (buff.equals(columns.buffOverride())) {
        armed = true;
        return columns.buffToConsider();
      }
      return buff;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (!armed) {
        return;
      }
      host.renewSpawnImmunity();
      int remaining = host.firstRemainingMs(columns.buffToConsider());
      int toDeath = remaining == NO_INSTANCE ? NO_INSTANCE : remaining + columns.delayMs();
      boolean wasAboutToDie = aboutToDie;
      if (toDeath >= columns.portalTimerMs()) {
        aboutToDie = false;
      } else {
        aboutToDie = true;
        if (!wasAboutToDie && columns.onAboutToDie() != null) {
          holder.schedule(columns.onAboutToDie(), ActionHolder.OWN_DELAY, false, holder);
        }
      }
      if (host.carries(columns.buffToConsider())) {
        timerMs = 0;
        return;
      }
      timerMs += STEP_MS;
      if (timerMs >= columns.delayMs()) {
        if (columns.actionToExecute() != null) {
          holder.schedule(columns.actionToExecute(), ActionHolder.OWN_DELAY, false, holder);
        }
        finish();
      }
    }
  }
}
