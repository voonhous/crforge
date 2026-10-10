/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

/**
 * The damage an area effect of the filter form deals to each object it reaches: a damage type,
 * written inline on the row or named from the damage types table, that gives a first-level amount
 * for anything and another for a crown tower.
 *
 * <p>The amount a hit starts from is the tower amount for a crown tower when the type gives one (0
 * or more), and the base amount otherwise. The hit is queued and dealt after the tick's post-hooks:
 * nothing to a target that takes no damage, the amount scaled by the area effect's level against
 * its rarity as card damage, then lowered by the target's protection, floored at 0.
 *
 * @param name the damage types row's name, or null for a type written inline
 * @param baseDamage the first-level amount for anything but a crown tower with a tower amount
 * @param towerDamage the first-level amount for a crown tower; below 0 for none, the base amount
 *     then applying to a crown tower too
 */
public record AreaDamageType(String name, int baseDamage, int towerDamage) {

  /** The tower amount a type that gives none has. */
  public static final int NO_TOWER_DAMAGE = -1;

  /**
   * The first-level amount a hit of this type starts from.
   *
   * @param crownTower true when the target is a crown tower
   */
  public int amount(boolean crownTower) {
    return crownTower && towerDamage >= 0 ? towerDamage : baseDamage;
  }
}
