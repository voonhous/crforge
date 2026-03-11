package org.crforge.core.combat;

import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Tower;

/** Shared damage calculation utilities used by CombatSystem and AreaEffectSystem. */
public final class DamageUtil {

  private DamageUtil() {}

  /**
   * Adjusts damage for crown tower damage reduction. Units like Miner deal reduced damage to
   * Towers. Formula: effectiveDamage = baseDamage * (100 + crownTowerDamagePercent) / 100 Example:
   * crownTowerDamagePercent = -75 means 25% damage to towers.
   */
  public static int adjustForCrownTower(
      int baseDamage, Entity target, int crownTowerDamagePercent) {
    if (crownTowerDamagePercent == 0 || !(target instanceof Tower)) {
      return baseDamage;
    }
    return Math.max(1, baseDamage * (100 + crownTowerDamagePercent) / 100);
  }
}
