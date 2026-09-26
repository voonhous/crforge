package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * An action that gives its owner a shield of a percentage of its maximum hit points, the percentage
 * clamped to 0..100 and the division truncating. An owner without hit points, or with a maximum of
 * zero, gets nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the percentage of the maximum, its clamps, the"
            + " truncating division, and nothing for an owner without hit points or with a maximum"
            + " of zero. Not modelled: a shield that expires, which no row of the data asks for.")
public final class SetShield extends RowAction {

  private final int shieldPercent;

  /**
   * @param row the row's shared columns
   * @param shieldPercent the shield as a percentage of the owner's maximum hit points
   */
  public SetShield(ActionRow row, int shieldPercent) {
    super(row);
    this.shieldPercent = shieldPercent;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ActionOwner owner = holder.getOwner();
    HitPoints hp = owner == null ? null : owner.actionHitPoints();
    if (hp == null || hp.getMaximum() == 0) {
      return null;
    }
    int percent = Math.max(0, Math.min(shieldPercent, 100));
    hp.setShield(percent * hp.getMaximum() / 100);
    return null;
  }
}
