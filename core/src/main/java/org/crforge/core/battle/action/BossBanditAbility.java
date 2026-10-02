package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * An action that locks its unit on itself and, a fixed number of battle ticks after it starts, runs
 * its warp on the unit, as the Boss Bandit's ability does.
 *
 * <p>Its start reads the battle tick and keeps the warp tick, the tick plus the warp delay in
 * steps, and the lock tick, the tick plus the lock delay in steps; with a lock delay under one step
 * either way it asks the battle's target locks at once for the unit itself on channel 0, which the
 * tick's post-pass grants. The lock locks nothing of the unit's own: only the channel-0 users, a
 * hook's drag among them, read it, and they may not take the unit while it is held.
 *
 * <p>Each step, from the run pass: while a release countdown runs, it takes a step off it and, with
 * none left, finishes, queueing the lock's release, which the next pre-pass acts on. Otherwise,
 * until the lock is held: before the lock tick it waits; then it claims the lock, and asks for it
 * again while it is not granted. Then it waits while the unit dashes; before the warp tick it
 * waits; on it, it schedules the warp row on the unit, the unit as its cause - outside a pending
 * pass, so the row runs in the unit's phase-2 pass of the same tick - and starts the release
 * countdown.
 *
 * <p>Refused as the row is built: tags, a singleton, a next action and the gates, none of which the
 * shipped rows set; a row with either speed byte clear, which would ask the unit's speed scalers;
 * and a row with no warp row, or a release delay of 0, which would finish in the warp's own step.
 * As it starts: an owner other than a character.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the warp and lock ticks from the battle tick, the lock asked at"
            + " once, its claim and second ask, the wait while dashing, the warp row scheduled on"
            + " the unit itself on the warp tick, the release countdown, the finish and the"
            + " release it queues. Held by boss_bandit_ability_tower and"
            + " boss_bandit_ability_charges. Refused: tags, a singleton, a next action, the gates,"
            + " a speed byte clear, no warp row, a release delay of 0 and an owner other than a"
            + " character.")
public final class BossBanditAbility extends RowAction {

  /** The step every timer takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The channel the lock is asked on. */
  private static final int CHANNEL = 0;

  /** The lock's priority. */
  private static final int PRIORITY = 1000;

  /** The lock's flags: bit 1, so a second ask by the holder answers that it holds it. */
  private static final int FLAGS = 2;

  /**
   * The row's own columns.
   *
   * @param warpDelayMs how long after the start the warp runs
   * @param lockDelayMs how long after the start the lock is asked for
   * @param releaseLockDelayMs how long after the warp the run holds the lock
   * @param warpAction the row it schedules on the unit on the warp tick
   * @param waitForDashToFinish true to wait while the unit dashes
   */
  @Builder
  public record Columns(
      int warpDelayMs,
      int lockDelayMs,
      int releaseLockDelayMs,
      BattleAction warpAction,
      boolean waitForDashToFinish) {}

  /** What the run asks of the battle about the unit it runs on. */
  public interface Host {

    /** The battle tick the step runs in. */
    int tick();

    /** The unit's id, which owns the lock and is its target. */
    int id();

    /** The battle's target locks. */
    TargetLocks locks();

    /** The unit's entity state. */
    int state();

    /**
     * The run's start, told to the battle's observers.
     *
     * @param action the row
     * @param phase the pending pass it started in
     * @param warpTick the tick it warps on
     * @param lockTick the tick it claims the lock from
     * @param requests the answer of each ask for the lock, in order
     */
    void started(
        BossBanditAbility action, int phase, int warpTick, int lockTick, List<Boolean> requests);

    /**
     * A step of the run that changed it or asked for the lock again, told to the battle's
     * observers.
     *
     * @param locked whether it holds the lock after the step
     * @param releaseMs the release countdown after the step
     * @param calls what the step did, in order
     */
    void stepped(boolean locked, int releaseMs, List<String> calls);
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public BossBanditAbility(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    Host host = holder.getOwner().bossBanditHost(this);
    int tick = host.tick();
    // Milliseconds to steps divide toward zero, as the start does.
    Run run =
        new Run(
            host, columns.warpDelayMs() / STEP_MS + tick, columns.lockDelayMs() / STEP_MS + tick);
    List<Boolean> requests = new ArrayList<>();
    // A lock delay within one step either way asks for the lock at once.
    if (Integer.compareUnsigned(columns.lockDelayMs() + 49, 98) <= 0) {
      requests.add(host.locks().request(host.id(), host.id(), CHANNEL, PRIORITY, FLAGS));
    }
    host.started(this, holder.passPhase(), run.warpTick, run.lockTick, requests);
    return run;
  }

  /** One run on the unit. */
  private final class Run extends ActionInstance {

    private final Host host;
    private final int warpTick;
    private final int lockTick;
    private boolean locked;
    private int releaseMs;

    private Run(Host host, int warpTick, int lockTick) {
      super(BossBanditAbility.this);
      this.host = host;
      this.warpTick = warpTick;
      this.lockTick = lockTick;
    }

    @Override
    protected void update(ActionHolder holder) {
      boolean lockedBefore = locked;
      int releaseBefore = releaseMs;
      List<String> calls = new ArrayList<>();
      step(holder, calls);
      if (locked != lockedBefore
          || releaseMs != releaseBefore
          || isFinished()
          || calls.stream().anyMatch(c -> c.startsWith("request"))) {
        host.stepped(locked, releaseMs, calls);
      }
    }

    private void step(ActionHolder holder, List<String> calls) {
      if (releaseMs >= 1) {
        int before = releaseMs;
        releaseMs = before - STEP_MS;
        if (before > STEP_MS) {
          return;
        }
        finish();
        // The finish hook queues the release, which the next pre-pass acts on.
        host.locks().release(host.id(), host.id(), CHANNEL);
        calls.add("finish");
        return;
      }
      int tick = host.tick();
      if (!locked) {
        if (tick < lockTick) {
          return;
        }
        boolean claim = host.locks().claim(host.id(), host.id(), CHANNEL);
        calls.add("claim " + claim);
        if (!claim) {
          boolean request = host.locks().request(host.id(), host.id(), CHANNEL, PRIORITY, FLAGS);
          calls.add("request " + request);
          return;
        }
        locked = true;
      }
      if (columns.waitForDashToFinish() && host.state() == GridEntityState.DASHING) {
        return;
      }
      if (tick < warpTick) {
        return;
      }
      // Scheduled from the run pass, outside every pending pass: the unit's phase-2 pass of the
      // tick takes it.
      holder.schedule(columns.warpAction(), ActionHolder.OWN_DELAY, false, holder);
      calls.add("warp " + columns.warpAction().name());
      releaseMs = columns.releaseLockDelayMs();
    }
  }
}
