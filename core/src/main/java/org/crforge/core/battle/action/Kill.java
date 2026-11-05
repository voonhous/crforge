/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that kills its owner: its kill action is scheduled on the owner first, and the owner is
 * then killed with the entity that caused the action as its killer, the kill landing at the damage
 * drain of the tick. An owner without hit points is left alone, its kill action included, except an
 * area effect, which the game ends instead (its kill action scheduled on it, then its countdown set
 * below 0): that end is refused rather than guessed, as no recording shows it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the kill action scheduled first, the kill with the cause's entity as killer, and"
            + " nothing without hit points. Read from the build's perform, not modelled: an area"
            + " effect owner's end, refused. The evolved Giant's rage areas schedule such a kill,"
            + " but in every recorded battle an area ends on its own before the kill's delay has"
            + " run, so no kill starts there.")
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
    if (owner != null && owner.actionAreaEffect()) {
      // The game schedules the kill action on the area, the area its own cause, and ends it.
      throw new UnsupportedOperationException(
          name() + " kills an area effect, whose end by a kill is not modelled");
    }
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
