package org.crforge.core.battle.action;

import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * The Mega Minion hero's hand-over: a run that lasts beside its mark, takes the mark's target and,
 * while its owner deploys, runs one of two actions by whether there is one. Started alongside the
 * mark, as the mark's next action, it is re-triggered by the ability's warp row to launch the warp
 * at the target.
 *
 * <p>Each step of the run:
 *
 * <ol>
 *   <li>It looks for the run of its ActionToGetTargetFrom row on its owner's holder; with none it
 *       finishes.
 *   <li>It takes the mark's target, keeping the one it took before when the mark has none, and
 *       records where that target stands.
 *   <li>In the deploying state it schedules HasTargetOnDeployAction with a target, or
 *       NoTargetOnDeployAction without one, on the owner, the owner as its cause.
 * </ol>
 *
 * <p>A leave notice of the target it holds drops it.
 *
 * <p>Refused rather than guessed, at the step that reaches it: the warp a re-trigger launches.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the mark looked up on the holder each step and the finish without it, the"
            + " deploying state's choice without a target scheduled on the owner; held by"
            + " hero_mega_minion. The mark's target taken and kept, its position recorded, and"
            + " leave notices of other objects; held by ability_hero_mega_minion_vs_musketeer."
            + " Refused: the warp a re-trigger launches.")
public final class MegaMinionHeroAbility extends RowAction {

  /**
   * The row's columns besides the shared ones.
   *
   * @param markRow the name of the row whose run gives the target
   * @param actionToExecute the name of the row a re-trigger launches at the target
   * @param noTargetOnDeploy run while the owner deploys without a target, or null
   * @param hasTargetOnDeploy run while the owner deploys with a target, or null
   */
  @Builder
  public record Columns(
      String markRow,
      String actionToExecute,
      BattleAction noTargetOnDeploy,
      BattleAction hasTargetOnDeploy) {}

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
      SetIndicatorOnTarget.Run mark = null;
      for (ActionInstance run : holder.running()) {
        if (run instanceof SetIndicatorOnTarget.Run found
            && run.getAction().name().equals(columns.markRow())) {
          mark = found;
          break;
        }
      }
      if (mark == null) {
        finish();
        return;
      }
      // A target once taken is kept while the mark has none.
      if (mark.target() != null) {
        target = mark.target();
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
        throw new UnsupportedOperationException(
            handOver.name()
                + " is re-triggered to launch "
                + columns.actionToExecute()
                + " at "
                + (target == null
                    ? "no target"
                    : target.rowName() + " (" + targetX + ", " + targetY + ")")
                + ", which is not modelled");
      }
    }
  }
}
