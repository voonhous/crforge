package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that kills its owner: its kill action is scheduled on the owner first, and the owner is
 * then killed with the entity that caused the action as its killer. An owner without hit points is
 * left alone, its kill action included.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the kill action scheduled first, the kill with the cause's entity as killer, and"
            + " nothing without hit points. Not modelled: an area effect's destroy, which comes with"
            + " the area effects.")
public final class Kill extends RowAction {

  private final BattleAction onKillAction;

  /**
   * @param row the row's shared columns
   * @param onKillAction scheduled on the owner before the kill, or null
   */
  public Kill(ActionRow row, BattleAction onKillAction) {
    super(row);
    this.onKillAction = onKillAction;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner owner = holder.getOwner();
    if (owner == null || owner.actionHitPoints() == null) {
      return null;
    }
    if (onKillAction != null) {
      holder.schedule(onKillAction, ActionHolder.OWN_DELAY, false, holder);
    }
    owner.killBy(instigator == null ? null : instigator.getOwner());
    return null;
  }
}
