package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that deals damage to its owner: when it starts it queues a typed hit of its base amount
 * and type on the owner, with the entity that caused the action as the source. The battle deals the
 * hit later in the tick.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the owner as the target, the cause as the source, the base amount and type"
            + " queued, not dealt. Not modelled: the filtered amounts, which no row of the data uses.")
public final class DealDamage extends RowAction {

  private final int baseDamageAmount;
  private final DamageType baseDamageType;

  /**
   * @param row the row's shared columns
   * @param baseDamageAmount the amount
   * @param baseDamageType its damage type
   */
  public DealDamage(ActionRow row, int baseDamageAmount, DamageType baseDamageType) {
    super(row);
    this.baseDamageAmount = baseDamageAmount;
    this.baseDamageType = baseDamageType;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner owner = holder.getOwner();
    if (owner != null) {
      owner.queueTypedHit(
          instigator == null ? null : instigator.getOwner(), baseDamageAmount, baseDamageType);
    }
    return null;
  }
}
