package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * An action that sets its owner's shield to a percentage of the shield's maximum (ShieldHitpoints
 * at the owner's level), not of its maximum hit points, the percentage clamped to 0..100 and the
 * division truncating. The maximum itself is left as it is. An owner without hit points, or whose
 * shield maximum is below 1, gets nothing: its shield is not written at all, not even by a 0
 * percent.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the percentage of the shield maximum (0 and 100"
            + " percent), the truncating division. Settled, not reached by a recorded case: the"
            + " clamps, and nothing written for an owner without hit points or with a shield"
            + " maximum below 1. Not modelled: a shield that expires, which no row of the data asks"
            + " for.")
public final class SetShield extends RowAction {

  private final int shieldPercent;

  /**
   * @param row the row's shared columns
   * @param shieldPercent the shield as a percentage of the owner's shield maximum
   */
  public SetShield(ActionRow row, int shieldPercent) {
    super(row);
    this.shieldPercent = shieldPercent;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ActionOwner owner = holder.getOwner();
    HitPoints hp = owner == null ? null : owner.actionHitPoints();
    // The shield maximum, read first: below 1 nothing at all is written.
    int shieldMaximum = hp == null ? 0 : hp.getShieldMaximum();
    if (shieldMaximum < 1) {
      return null;
    }
    int percent = Math.max(0, Math.min(shieldPercent, 100));
    hp.setShield(percent * shieldMaximum / 100);
    return null;
  }
}
