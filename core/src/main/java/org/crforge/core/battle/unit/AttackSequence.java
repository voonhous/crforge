package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.pathfinding.target.AttackSequenceEntry;

/**
 * A unit's attack sequence as the loader leaves it: the entries, the order they are used in, and
 * the mode that says who moves the index into the order.
 *
 * <p>Every unit has one: a row without a sequence keeps an order of one element and the mode 0, so
 * its index stays 0. The entries replace the row's own projectile and damage only when the order
 * has at least two elements, while an entry's action is read at any length; every lookup is the
 * entry the order names at the index.
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

  /**
   * The mode in which only an action moves the index, as in mode 0, while the attack is a
   * continuous-damage attacker's in every other respect: the attack range closer while it walks,
   * the attack reset in place of the starting attack action on a new reference in range, the reset
   * on a lost or replaced reference and on a broken shield, the drop of a reference out of range
   * while the timer runs and the refusal of an untouchable target. No window is walked: the
   * entries' variable damage times are not read.
   */
  public static final int MODE_MANUAL = 4;

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
   * @param meleePushbackAll true when that push lifts the gates that would refuse it: the target's
   *     row or buffs ignoring pushback, its no-pushback flag or its being dragged
   * @param doAttackAction the action the entry's hit schedules instead of hitting, or null
   * @param customFirstProjectile the projectile the first of each hit's projectiles is instead of
   *     the row's custom first projectile, or null to keep the row's; read in an order of two or
   *     more, from the entry at the index itself rather than the one the order names there
   * @param customMultipleTargets how many targets each hit reaches, or -1 for the row's
   *     MultipleTargets; read at any length of the order
   * @param customRememberMultipleTargets whether each hit remembers the targets it reached: -1 for
   *     the row's RememberMultipleTargets, 0 for no, anything else for yes
   * @param customOnAttackAction the action each hit schedules in place of the row's OnAttackAction,
   *     or null to keep the row's; read at any length of the order
   * @param attackStartDelay milliseconds the attack timer is held at 0 when an attack starts while
   *     the index selects the entry
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
      boolean meleePushbackAll,
      String doAttackAction,
      ProjectileData customFirstProjectile,
      int customMultipleTargets,
      int customRememberMultipleTargets,
      String customOnAttackAction,
      int attackStartDelay) {

    /**
     * True when the entry sets a variable damage time outside a timer-driven mode, which walks no
     * window there. Every other column it sets is read: the two ranges replace the row's Range and
     * MinimumRange whenever the index selects the entry, at any length of the order, the owner's
     * collision radius still added; the sight range, the launch's start height and distance and,
     * through the attack timer, the hit speed multiplier in an order of two or more; and the
     * columns of a newer data version as each describes.
     */
    public boolean overridesMore() {
      return variableDamageTime != 0;
    }

    /**
     * The entry as the targeting component's range, sight, timer and multi-target helpers read it:
     * its attack range, minimum range and sight range overrides, -1 for none, its hit speed
     * multiplier, its number of targets, -1 for none, whether it remembers them, -1 for the row's
     * answer, and its start delay.
     */
    public AttackSequenceEntry targetingEntry() {
      return new AttackSequenceEntry(
          customRange,
          customMinimumRange,
          customSightRange,
          hitSpeedMultiplier,
          customMultipleTargets,
          customRememberMultipleTargets,
          attackStartDelay);
    }
  }

  /**
   * The entries as the targeting component's helpers read them, each by its own index; the order
   * names which one an index selects.
   */
  public List<AttackSequenceEntry> targetingEntries() {
    return entries.stream().map(Entry::targetingEntry).toList();
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
