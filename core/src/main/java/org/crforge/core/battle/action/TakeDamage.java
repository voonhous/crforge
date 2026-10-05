package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that deals damage to its owner, as the Ronin's reflect does: as it starts on an owner
 * with hit points it evaluates AddedDamage with the context its start carried (0 without one) and
 * queues a hit of its Damage on the owner, the entity that caused the action its source. The
 * battle's damage drain deals it later in the tick: the damage's amount for the target - its tower
 * amount against a crown tower when it gives one, its base amount else - plus the added amount; the
 * source's level scaling unless NoScaling; the source's percentages, floored at 0, unless
 * NoAmplification; the target's protection, floored at 0, unless NoProtection; then the damage
 * entry, which tells the target's runs whether the hit is a Reflected one.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the owner with hit points, the added amount evaluated with the start's context,"
            + " the source, the queue, the amount at the drain and the flags read. Supplied: no"
            + " attacker's buff changes the amount. Refused: a damage without NoScaling, whose"
            + " level scaling is untraced, and the flags and columns no row of the data needs.")
public final class TakeDamage extends RowAction {

  /** A tower amount a damage that gives none carries. */
  public static final int NO_TOWER_DAMAGE = -1;

  /**
   * The damage written inline in the row.
   *
   * @param baseDamage the amount against anything but a crown tower, and against one without a
   *     tower amount
   * @param towerDamage the amount against a crown tower, or {@link #NO_TOWER_DAMAGE}
   * @param reflected true under the Reflected flag, which a counter never counters
   * @param noProtection true under NoProtection: the target's protection is skipped
   * @param noAmplification true under NoAmplification: the source's percentages are skipped
   */
  public record Damage(
      int baseDamage,
      int towerDamage,
      boolean reflected,
      boolean noProtection,
      boolean noAmplification) {

    /**
     * The amount against a target, before the added amount.
     *
     * @param crownTower true when the target is a crown tower
     */
    public int amount(boolean crownTower) {
      return crownTower && towerDamage >= 0 ? towerDamage : baseDamage;
    }
  }

  private final Damage damage;

  private final IntSupplier addedDamage;

  /**
   * @param row the row's shared columns
   * @param damage the damage
   * @param addedDamage the added amount's expression, or null for 0
   */
  public TakeDamage(ActionRow row, Damage damage, IntSupplier addedDamage) {
    super(row);
    this.damage = damage;
    this.addedDamage = addedDamage;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    ActionOwner owner = holder.getOwner();
    if (owner == null || owner.actionHitPoints() == null) {
      return null;
    }
    int added = addedDamage == null ? 0 : addedDamage.getAsInt();
    owner.queueActionDamage(instigator == null ? null : instigator.getOwner(), damage, added);
    return null;
  }
}
