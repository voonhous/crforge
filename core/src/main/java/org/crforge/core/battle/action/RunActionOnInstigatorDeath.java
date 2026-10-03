package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that waits for what caused it to leave the battle, as each unit the evolved Snowball
 * captures waits for the snowball: its run keeps the id of its cause, and each step looks the id up
 * in the battle's live list. While the cause is listed, dead or not, nothing happens; once it is
 * not, the row's action is scheduled on the object the run is on, with that object as its cause,
 * and the run finishes.
 *
 * <p>Refused as it starts: a run with no cause.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the cause's id at the start, the wait while it is listed and the"
            + " action on the object, itself the cause, once it is not; held by"
            + " firecracker_snowball_goblins, where each released Goblin takes the snowball's"
            + " after-release slow the tick after the snowball leaves. Refused: a run with no"
            + " cause.")
public final class RunActionOnInstigatorDeath extends RowAction {

  /** The action scheduled once the cause has left. */
  @Getter private final BattleAction actionToRun;

  /**
   * @param row the row's shared columns
   * @param actionToRun the action scheduled once the cause has left
   */
  public RunActionOnInstigatorDeath(ActionRow row, BattleAction actionToRun) {
    super(row);
    this.actionToRun = actionToRun;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    throw new UnsupportedOperationException(name() + " runs with no cause, not modelled");
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator == null) {
      return start(holder);
    }
    return new Run(instigator.getOwner().actionId());
  }

  /** One run: the id of its cause. */
  private final class Run extends ActionInstance {

    private final int instigatorId;

    private Run(int instigatorId) {
      super(RunActionOnInstigatorDeath.this);
      this.instigatorId = instigatorId;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (holder.getOwner().liveObject(instigatorId)) {
        return;
      }
      holder.getOwner().instigatorGone(RunActionOnInstigatorDeath.this, actionToRun);
      holder.schedule(actionToRun, ActionHolder.OWN_DELAY, false, holder);
      finish();
    }
  }
}
