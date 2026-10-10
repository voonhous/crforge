/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Dart Goblin's poison damage: the run a poison area's hit starts on the object it
 * reaches, one per object and thrower, which damages the object every HitSpeed while it lasts.
 *
 * <p>The amount is the DamageList entry of the stack the area's controller copy keeps, scaled to
 * the area's level, and on a crown tower CrownDamageDamageMultiplier percent of that. A start or a
 * re-trigger puts the duration (Duration, or CrownTowerDuration on a crown tower) back and raises
 * the amount when the new one is higher. Each step adds 50 ms to the run's clock and takes 50 ms
 * off the duration; on each whole HitSpeed of the clock the object takes the amount, with no
 * attacker, and once the duration is out the run finishes.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the run per object and thrower, the amount from the copy's stack, the area's"
            + " level and the crown tower share, the duration put back by each hit and the damage"
            + " on each whole HitSpeed; held by evo_blowdartgoblin_vs_musketeer. Supplied: the"
            + " amount is scaled as an area effect's damage is. Refused as the row is built:"
            + " OnHitAction, which no shipped row sets.")
public final class BlowdartDamage extends RowAction {

  /**
   * The row's own columns.
   *
   * @param controller the controller row whose copy on the area keeps the stack
   * @param durationMs how long the run lasts after a hit
   * @param hitSpeedMs the time between two damages
   * @param crownTowerDurationMs the same as the duration on a crown tower, or -1 to keep Duration
   * @param crownDamageMultiplier the percentage of the amount a crown tower takes, below 1 for all
   * @param damageList the amount of each stack, at the first level
   */
  @Builder
  public record Columns(
      String controller,
      int durationMs,
      int hitSpeedMs,
      int crownTowerDurationMs,
      int crownDamageMultiplier,
      List<Integer> damageList) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public BlowdartDamage(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return holder.getOwner().blowdartDamage(this, instigator);
  }
}
