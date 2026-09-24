package org.crforge.core.battle.projectile;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.target.DirectHit;

/**
 * What a projectile deals on arrival: its row's damage at the projectile's level, and the crown
 * tower's share of it.
 *
 * <p>The projectile's level is the launcher's, re-based on the projectile row's own rarity at the
 * launch, so a projectile fired by a unit of another rarity still scales by its own table. The
 * crown-tower damage is the plain damage under the row's crown-tower percentage, rounded up, by the
 * same rule a direct hit applies.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the damage at the projectile's level under the row's own rarity and scaling"
            + " mode, and the crown-tower damage from it. Not modelled: the heal and tower heal"
            + " amounts, the reduction of a deflected projectile's amounts, and the launcher's"
            + " buffs changing either damage.")
public final class ProjectileAmounts {

  private ProjectileAmounts() {
    // Utility class
  }

  /** The projectile's damage at its level. */
  public static int damage(ScalingGlobals globals, ProjectileData data, int packedLevel) {
    return LevelScaling.scale(
        globals, data.damage(), packedLevel, data.damageMode(), data.rarity());
  }

  /** What a crown tower takes from the projectile at its level. */
  public static int towerDamage(ScalingGlobals globals, ProjectileData data, int packedLevel) {
    return DirectHit.crownTowerDamage(
        data.crownTowerDamagePercent(), damage(globals, data, packedLevel));
  }
}
