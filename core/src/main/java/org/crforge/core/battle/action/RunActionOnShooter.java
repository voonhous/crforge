package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that hands its action back to whoever shot its owner: when it starts on a projectile it
 * schedules ActionToExecute on the holder of the entity that launched the projectile, built for
 * that entity, with the projectile as its cause, the row's own delay, not at once and with no
 * context. On an owner that is not a projectile, or a projectile with no launcher, it schedules
 * nothing; a row without an action does nothing. The run's own cause and context are not read.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: the perform reads the owner's kind, and for a projectile with a launcher"
            + " schedules the action on the launcher with the projectile as its cause, its own"
            + " delay, not at once and without a context; the action's own at-once slot answers"
            + " false for a group, the only class the shipped rows hand back.")
public final class RunActionOnShooter extends RowAction {

  /** The name of the row run on the shooter, or null for none. */
  private final String actionToExecute;

  /**
   * @param row the row's shared columns
   * @param actionToExecute the name of the row scheduled on the shooter, or null for none
   */
  public RunActionOnShooter(ActionRow row, String actionToExecute) {
    super(row);
    this.actionToExecute = actionToExecute;
  }

  /** The name of the row run on the shooter, or null for none. */
  public String actionToExecute() {
    return actionToExecute;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (actionToExecute != null && holder.getOwner() != null) {
      holder.getOwner().runOnShooter(this, actionToExecute);
    }
    return null;
  }
}
