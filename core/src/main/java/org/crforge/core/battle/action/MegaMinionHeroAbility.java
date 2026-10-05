package org.crforge.core.battle.action;

import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * The Mega Minion hero's hand-over: a run that lasts beside its mark, takes the mark's target and,
 * while its owner deploys, runs one of two actions by whether there is one. Started alongside the
 * mark, as the mark's next action, it is re-triggered by the ability's warp row to launch the warp
 * at the target; a row that returns to the origin then flies its owner back to where it launched.
 *
 * <p>Each step of the run:
 *
 * <ol>
 *   <li>It looks for the run of its ActionToGetTargetFrom row on its owner's holder; with none it
 *       finishes.
 *   <li>With a context, it writes its warp window into the context's main board: under
 *       WarpWindowActiveKey 1 while the run is anywhere but idle and 0 when idle, and, when not
 *       idle, the origin under WarpWindowOriginXKey and WarpWindowOriginYKey. A key column left out
 *       names the key WarpWindowActive, WarpWindowOriginX or WarpWindowOriginY.
 *   <li>Then by its state:
 *       <ul>
 *         <li><b>Idle.</b> It takes the mark's target; with none from the mark it keeps the one it
 *             took before while that object is still in the battle, and drops it otherwise. It
 *             records where that target stands. In the deploying state it schedules
 *             HasTargetOnDeployAction with a target, or NoTargetOnDeployAction without one, on the
 *             owner, the owner as its cause. After a re-trigger it records the owner's position as
 *             the origin and launches the warp, ActionToExecute: built for the owner, handed the
 *             target and the position recorded for it, listed on the holder and started. With
 *             ReturnToOrigin and a ReturnWarpAction the run then flies.
 *         <li><b>Flying.</b> While a run of ActionToExecute is listed on the holder nothing
 *             happens; once none is, the run waits from this battle tick.
 *         <li><b>Waiting.</b> With ReturnOnTargetDeath, a target gone from the battle (or none)
 *             returns at once. With a ReturnDelay of 1 or more the run returns once the
 *             milliseconds since the wait began, 50 a battle tick, have reached it. With no delay
 *             it returns at once, unless ReturnOnTargetDeath holds it for the target's death.
 *         <li><b>Returning.</b> While a run of ReturnWarpAction is listed nothing happens; once
 *             none is, the run is idle again.
 *       </ul>
 * </ol>
 *
 * <p>The return launches ReturnWarpAction for the owner with no target and the origin as the
 * position recorded for it, lists and starts it, and the run is returning. A listed run of a row is
 * one whose row is a singleton, finished or not, as the holder's lookup finds it.
 *
 * <p>A leave notice of the target it holds drops it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the mark looked up on the holder each step and the finish without it, the"
            + " deploying state's choice without a target scheduled on the owner; held by"
            + " hero_mega_minion. The mark's target taken and kept, its position recorded, and"
            + " leave notices of other objects, and the warp a re-trigger launches with the target"
            + " and its position; held by ability_hero_mega_minion_vs_musketeer. The four states,"
            + " the warp window written into the context, the wait by the battle clock and the"
            + " return to the origin; held by BattleMegaMinionReturnTest.")
public final class MegaMinionHeroAbility extends RowAction {

  /** The key a WarpWindowActiveKey left out names. */
  public static final String DEFAULT_ACTIVE_KEY = "WarpWindowActive";

  /** The key a WarpWindowOriginXKey left out names. */
  public static final String DEFAULT_ORIGIN_X_KEY = "WarpWindowOriginX";

  /** The key a WarpWindowOriginYKey left out names. */
  public static final String DEFAULT_ORIGIN_Y_KEY = "WarpWindowOriginY";

  /** Milliseconds one battle tick adds to the wait before the return. */
  private static final int TICK_MS = 50;

  /** The run's states, as the game numbers them: idle, flying, waiting, returning. */
  private static final int IDLE = 0;

  private static final int FLYING = 1;
  private static final int WAITING = 2;
  private static final int RETURNING = 3;

  /**
   * The row's columns besides the shared ones.
   *
   * @param markRow the name of the row whose run gives the target
   * @param actionToExecute the name of the row a re-trigger launches at the target
   * @param noTargetOnDeploy run while the owner deploys without a target, or null
   * @param hasTargetOnDeploy run while the owner deploys with a target, or null
   * @param returnToOrigin true when the owner flies back to its origin after the warp
   * @param returnOnTargetDeath true when the target's death ends the wait before the return
   * @param returnDelayMs the wait before the return, in milliseconds; below 1 for none
   * @param returnWarpRow the name of the row that flies the owner back, or null for none
   * @param activeKey the key the warp window's state is written under
   * @param originXKey the key the origin's position along the width is written under
   * @param originYKey the key the origin's position along the length is written under
   */
  @Builder
  public record Columns(
      String markRow,
      String actionToExecute,
      BattleAction noTargetOnDeploy,
      BattleAction hasTargetOnDeploy,
      boolean returnToOrigin,
      boolean returnOnTargetDeath,
      int returnDelayMs,
      String returnWarpRow,
      int activeKey,
      int originXKey,
      int originYKey) {}

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public MegaMinionHeroAbility(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  /** The row's own columns. */
  public Columns columns() {
    return columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(this, holder.getOwner().markHost(this));
  }

  /**
   * The listed run of a row on a holder, as the holder's lookup finds it: the first whose row has
   * the name and is a singleton, finished or not; null for none.
   */
  private static ActionInstance listed(ActionHolder holder, String row) {
    if (row == null) {
      return null;
    }
    for (ActionInstance run : holder.running()) {
      BattleAction action = run.getAction();
      if (action.singleton() && action.name().equals(row)) {
        return run;
      }
    }
    return null;
  }

  /** One run of the hand-over. */
  private static final class Run extends ActionInstance {

    private final MegaMinionHeroAbility handOver;
    private final SetIndicatorOnTarget.Host host;

    /** True once a re-trigger asked for the warp. */
    private boolean warpRequested;

    /** The target taken from the mark, or null for none. */
    private SetIndicatorOnTarget.Candidate target;

    /** Where the target stood when last taken, along the width and the length. */
    private int targetX;

    private int targetY;

    /** The run's state: idle, flying, waiting or returning. */
    private int state = IDLE;

    /** Where the owner stood as it launched the warp, along the width and the length. */
    private int originX;

    private int originY;

    /** The battle tick the wait before the return began on. */
    private int waitTick;

    private Run(MegaMinionHeroAbility handOver, SetIndicatorOnTarget.Host host) {
      super(handOver);
      this.handOver = handOver;
      this.host = host;
    }

    @Override
    protected void retrigger(ActionHolder holder) {
      warpRequested = true;
    }

    @Override
    protected void objectLeft(int leftId) {
      if (target != null && target.id() == leftId) {
        target = null;
      }
    }

    @Override
    protected void update(ActionHolder holder) {
      Columns columns = handOver.columns;
      if (!(listed(holder, columns.markRow()) instanceof SetIndicatorOnTarget.Run mark)) {
        finish();
        return;
      }
      // The warp window, written before the state moves: a step sees the state the last left.
      ActionContext context = context();
      if (context != null) {
        context.write(false, columns.activeKey(), state != IDLE ? 1 : 0);
        if (state != IDLE) {
          context.write(false, columns.originXKey(), originX);
          context.write(false, columns.originYKey(), originY);
        }
      }
      switch (state) {
        case IDLE -> idle(holder, mark);
        case FLYING -> {
          if (listed(holder, columns.actionToExecute()) == null) {
            state = WAITING;
            waitTick = holder.getOwner().actionClock(handOver).getAsInt();
          }
        }
        case WAITING -> {
          if (returnDue(holder)) {
            // The return flies to the origin with no target, listed and started like the warp.
            holder.list(
                holder
                    .getOwner()
                    .launchWarp(handOver, columns.returnWarpRow(), null, originX, originY));
            state = RETURNING;
          }
        }
        case RETURNING -> {
          if (listed(holder, columns.returnWarpRow()) == null) {
            state = IDLE;
          }
        }
        default -> {}
      }
    }

    /** The idle step: the target, the deploy actions and a requested warp. */
    private void idle(ActionHolder holder, SetIndicatorOnTarget.Run mark) {
      Columns columns = handOver.columns;
      if (mark.target() != null) {
        target = mark.target();
      } else if (target != null && !holder.getOwner().liveObject(target.id())) {
        // A target kept from before is dropped once it is gone from the battle.
        target = null;
      }
      if (target != null) {
        targetX = target.x();
        targetY = target.y();
      }
      if (host.state() == GridEntityState.DEPLOYING) {
        BattleAction deploy =
            target == null ? columns.noTargetOnDeploy() : columns.hasTargetOnDeploy();
        if (deploy != null) {
          holder.schedule(deploy, ActionHolder.OWN_DELAY, false, holder);
        }
      }
      if (warpRequested) {
        originX = host.x();
        originY = host.y();
        // The warp is built with the target and its last position, listed and started; the run
        // pass reaches it in this same pass, as it is listed after this run.
        holder.list(
            holder
                .getOwner()
                .launchWarp(handOver, columns.actionToExecute(), target, targetX, targetY));
        warpRequested = false;
        if (columns.returnToOrigin() && columns.returnWarpRow() != null) {
          state = FLYING;
        }
      }
    }

    /** Whether the wait before the return is over. */
    private boolean returnDue(ActionHolder holder) {
      Columns columns = handOver.columns;
      if (columns.returnOnTargetDeath()
          && (target == null || !holder.getOwner().liveObject(target.id()))) {
        return true;
      }
      if (columns.returnDelayMs() < 1) {
        // With no delay the return waits only for the target's death, when the row asks for it.
        return !columns.returnOnTargetDeath();
      }
      int now = holder.getOwner().actionClock(handOver).getAsInt();
      return (now - waitTick) * TICK_MS >= columns.returnDelayMs();
    }
  }
}
