package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that rolls the projectile it runs on along the ground, as the evolved Snowball's
 * rolling snowball rolls: a run that takes the place of the projectile's flight, whose visit moves
 * it not at all.
 *
 * <p>As it starts the run takes its destination: the row's distance forward for the projectile's
 * side, up the length for side 0 and down it for side 1, and its distance across toward the middle
 * of the arena, walked back toward the projectile in steps of 100 while the point is off the map or
 * on a cell of the not-placeable value alone in its two by two block. Each step of the run buffs,
 * once per object, everything its query finds within its radius around the projectile under its
 * filter, the projectile as the source at its level, and then moves the projectile its speed toward
 * the destination. The step that reaches it puts the projectile there, buffs again, and releases
 * it: the projectile leaves at that tick's cleanup with no impact, and the run finishes.
 *
 * <p>Refused as the row is built: the shared columns its run does not read, and a row without a
 * filter or a buff. As it starts: an owner other than a projectile. As it runs: a deflecting area
 * effect in the battle, which its deflection pass would ask.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the destination by side and toward the middle, walked back off"
            + " the blocked cells, the once-per-object buff of the query's finds with the"
            + " projectile as the source at its level, the step by speed and the arrival's last"
            + " buff and release with no impact; held by firecracker_snowball_goblins, where it"
            + " buffs four Goblins and a princess tower; the destination by side and walked back"
            + " also by BattleSnowballEvoTest. Held by no run: a distance across, which no shipped"
            + " row sets, the arrival's last buff reaching what the step before did not, and a"
            + " building the query takes by its square that a circle would not. Refused: a"
            + " deflection and the shared columns.")
public final class RollingProjectile extends RowAction {

  /**
   * The row's own columns.
   *
   * @param speed how far each step moves the projectile
   * @param distanceY how far forward for its side the destination lies
   * @param distanceX how far across, toward the middle, the destination lies
   * @param radius the radius of the query around the projectile
   * @param buffOnHit the buff each object found takes once
   * @param buffTimeMs how long that buff lasts
   * @param targetFilter the filter its query asks
   */
  @Builder
  public record Columns(
      int speed,
      int distanceY,
      int distanceX,
      int radius,
      String buffOnHit,
      int buffTimeMs,
      GameObjectFilter targetFilter) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public RollingProjectile(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().rollingProjectile(this, holder.passPhase());
  }
}
