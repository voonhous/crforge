package org.crforge.core.pathfinding.combat;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A heal of a hit-points object.
 *
 * <p>An amount below one does nothing. A shield that is up takes the whole heal, up to its maximum,
 * and the hit points are not touched. Otherwise a dead object is not healed, and the hit points
 * rise by the amount up to a cap: the larger of the hit points and the maximum, so over-heal
 * already there is kept; one less than that for a king tower below its maximum, which therefore
 * never heals back to full; else, with an over-heal percentage of one or more, that share of the
 * maximum, which replaces the cap and may even lie below the hit points, lowering them. All
 * arithmetic is 32-bit.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line and held by 2000 recorded cases and the worked heals of the data:"
            + " the amount below one, the shield, the dead object, the cap, the king tower's cap and"
            + " the over-heal percentage. Not modelled: the presentation and the player statistics"
            + " a heal that raised the value tells.")
public final class Healing {

  private Healing() {
    // Utility class
  }

  /**
   * Heals a hit-points object.
   *
   * @param hp the object
   * @param amount the heal
   * @param overHealPercent the share of the maximum the heal may reach; below one for none
   * @param kingTower true when the object belongs to a king tower
   */
  public static void heal(HitPoints hp, int amount, int overHealPercent, boolean kingTower) {
    if (amount < 1) {
      return;
    }
    if (hp.getShield() >= 1) {
      hp.setShield(Math.min(hp.getShieldMaximum(), hp.getShield() + amount));
      return;
    }
    int before = hp.getHitPoints();
    if (before < 1) {
      return;
    }
    int cap = Math.max(before, hp.getMaximum());
    if (kingTower && hp.getHitPoints() < hp.getMaximum()) {
      cap = cap - 1;
    } else if (overHealPercent >= 1) {
      cap = hp.getMaximum() * overHealPercent / 100;
    }
    hp.setHitPoints(Math.min(cap, hp.getHitPoints() + amount));
  }
}
