package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.projectile.ProjectileData;

/**
 * A unit's attack sequence as the loader leaves it: the entries, the order they are used in, and
 * the mode that says who moves the index into the order.
 *
 * <p>Every unit has one: a row without a sequence keeps an order of one element and the mode 0, so
 * its index stays 0. The entries replace the row's own projectile and damage only when the order
 * has at least two elements; every lookup is the entry the order names at the index.
 *
 * @param mode None 0, StaticLoop 1, HittimeLoop 2, Hittime 3, Manual 4
 * @param order the entries' indices, in the order the index walks them
 * @param entries the entries, which the order may name more than once or not at all
 */
public record AttackSequence(int mode, List<Integer> order, List<Entry> entries) {

  /** The mode in which only an action moves the index. */
  public static final int MODE_NONE = 0;

  /**
   * The mode in which every hit step moves the index on by one, around the order: after the hits of
   * each hit step the targeting visit stores the index plus one, modulo the order's length.
   */
  public static final int MODE_STATIC_LOOP = 1;

  /**
   * The mode of a continuous-damage attacker: the attack timer carries a ramp, and every attack
   * step stores the window its timer has reached into the index.
   */
  public static final int MODE_HITTIME = 3;

  /** The sequence of a row without one: one element, no entry read. */
  public static final AttackSequence NONE = new AttackSequence(MODE_NONE, List.of(0), List.of());

  public AttackSequence {
    order = List.copyOf(order);
    entries = List.copyOf(entries);
  }

  /**
   * One entry: what one attack of the sequence uses in place of the row's own columns.
   *
   * @param damage the entry's damage at the first level
   * @param projectile the entry's projectile, or null for none
   * @param variableDamageTime how long the entry lasts in the timer-driven modes, in milliseconds
   * @param hitSpeedMultiplier the pace of the attack timer during the entry, in percent
   * @param customRange the entry's attack range, or -1 for the row's
   * @param customSightRange the entry's sight range, or -1 for the row's
   * @param customMinimumRange the entry's minimum range, or -1 for the row's
   * @param customProjectileStartZ the entry's launch height, or -1 for the row's
   * @param customProjectileStartRadius the entry's launch distance, or -1 for the row's
   * @param meleePushback how far the entry's direct hit pushes its target
   * @param doAttackAction the action the entry's hit schedules instead of hitting, or null
   */
  public record Entry(
      int damage,
      ProjectileData projectile,
      int variableDamageTime,
      int hitSpeedMultiplier,
      int customRange,
      int customSightRange,
      int customMinimumRange,
      int customProjectileStartZ,
      int customProjectileStartRadius,
      int meleePushback,
      String doAttackAction) {

    /**
     * True when the entry sets anything but its damage, its projectile, its action and its direct
     * hit's pushback.
     */
    public boolean overridesMore() {
      return variableDamageTime != 0 || overridesMoreThanItsWindow();
    }

    /**
     * True when the entry sets anything but its damage, its projectile, its action, its direct
     * hit's pushback and its variable damage time, the window a timer-driven mode walks.
     */
    public boolean overridesMoreThanItsWindow() {
      return hitSpeedMultiplier != 100
          || customRange != -1
          || customSightRange != -1
          || customMinimumRange != -1
          || customProjectileStartZ != -1
          || customProjectileStartRadius != -1;
    }
  }

  /**
   * True when the entries replace the row's own projectile and damage: two or more in the order.
   */
  public boolean replacesAttack() {
    return order.size() >= 2;
  }

  /**
   * The window walk of an attack timer: the first index in the order whose window the timer has not
   * used up, taking each entry's variable damage time off it in turn, else the order's last index.
   * The timer-driven modes store it into the index on every attack step.
   */
  public int windowAt(int attackTimerMs) {
    int left = attackTimerMs;
    for (int index = 0; index < order.size(); index++) {
      left -= entryAt(index).variableDamageTime();
      if (left < 0) {
        return index;
      }
    }
    return order.size() - 1;
  }

  /** The entry the order names at an index. */
  public Entry entryAt(int index) {
    return entries.get(order.get(index));
  }
}
