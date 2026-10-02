package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Goblinstein's ability action: a run on the area effect that follows the doctor, which ties the
 * doctor to the unit made with it, tethers the two once the doctor has cast its ability, and makes
 * a death area where that unit leaves.
 *
 * <p>It does nothing as it starts but list its run, which the area effect's owner makes. Its first
 * step connects to the unit the doctor is grouped with: the first of the doctor's group chain, or
 * the one after it when the doctor is the first. Every later step waits for the doctor to cast its
 * ability, then for the cast to end, and then runs the tether for its duration: damage passes along
 * the segment from the area effect to the connected unit every hit interval, each hitting the
 * enemies the targets filter takes within the width of the segment. When the connected unit leaves,
 * the run makes its death area at the unit's point, once, and holds it in the unit's place, so a
 * later tether runs to the death area; when the area effect leaves, a death area still held ends.
 *
 * <p>The tags the tether sets on both ends each update are read by no battle code; the effects it
 * shows are presentation.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: the connection on the first step, the wait for the cast and for its end, the"
            + " tether's activation rows, damage passes, hits and hit actions, the death area on"
            + " the connected unit's leaving and its end as the owner leaves; held by"
            + " goblinstein_tower, goblinstein_doctor_first and goblinstein_ability_tower. The"
            + " tether's tags, which no battle code reads, are not set.")
public final class GoblinsteinAbility extends RowAction {

  /**
   * The row's columns the run reads.
   *
   * @param tetherDurationMs how long a tether lasts once the cast ends
   * @param deathAreaEffect the area effect made where the connected unit leaves, or null for none
   * @param tetherWidth how far either side of the segment a damage pass reaches
   * @param tetherDamage the damage of a pass's hit at the first level
   * @param tetherCrownTowerDamage the damage of a pass's hit on a crown tower at the first level; 0
   *     to take the tether's damage
   * @param tetherHitIntervalMs the time between two damage passes
   * @param tetherHitActionIntervalMs how long an object hit once is spared the hit action; equal to
   *     the hit interval for a hit action on every hit
   * @param tetherDamageTargets the filter of the objects a damage pass hits, or null for none
   * @param tetherHitAction the action scheduled on each object a damage pass reaches, or null
   * @param onTetherActivationAction the action scheduled on the area effect as a tether starts, or
   *     null
   * @param onTetherActivationActionOnConnectedUnit the action scheduled on the connected object as
   *     a tether starts, or null
   */
  public record Columns(
      int tetherDurationMs,
      String deathAreaEffect,
      int tetherWidth,
      int tetherDamage,
      int tetherCrownTowerDamage,
      int tetherHitIntervalMs,
      int tetherHitActionIntervalMs,
      GameObjectFilter tetherDamageTargets,
      String tetherHitAction,
      String onTetherActivationAction,
      String onTetherActivationActionOnConnectedUnit) {}

  /** The row's columns the run reads. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public GoblinsteinAbility(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().goblinsteinAbility(this, holder.passPhase());
  }
}
