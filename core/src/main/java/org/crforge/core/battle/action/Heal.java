package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.Healing;
import org.crforge.core.pathfinding.combat.HitPoints;

/** An action that heals its owner by its value, with its over-heal percentage. */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the owner healed by the value's expression, which is not level-scaled, with the"
            + " over-heal percentage, and nothing for an owner without hit points. Not modelled: a"
            + " flat value put through the owner's level scaling, which no row of the data uses.")
public final class Heal extends RowAction {

  private final IntSupplier value;
  private final int maxOverHealPercent;

  /**
   * @param row the row's shared columns
   * @param value the heal's expression
   * @param maxOverHealPercent the share of the maximum the heal may reach; 0 for none
   */
  public Heal(ActionRow row, IntSupplier value, int maxOverHealPercent) {
    super(row);
    this.value = value;
    this.maxOverHealPercent = maxOverHealPercent;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ActionOwner owner = holder.getOwner();
    HitPoints hp = owner == null ? null : owner.actionHitPoints();
    if (hp != null) {
      Healing.heal(hp, value.getAsInt(), maxOverHealPercent, owner.kingTower());
    }
    return null;
  }
}
