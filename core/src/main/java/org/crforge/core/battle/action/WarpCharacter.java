package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that moves its unit at once by a fixed offset toward its own side, as the Boss Bandit's
 * warp does, or one that flies its unit to an injected target over time, as the Mega Minion hero's
 * teleport does.
 *
 * <p>The instant warp: the destination is the unit's position plus the offset, both axes negated
 * for side 1, so a negative length offset carries either side's unit back toward its own king; it
 * is clamped into the arena. A landing cell that is water, or that is the blocked value alone, is
 * replaced by the nearest allowed cell of the same column within four rows, the nearer half-row
 * neighbour tried first at each distance, at that cell's centre; with none, the starting row's
 * centre. The unit is placed there in one write. Then its pending damage is reset, its route
 * emptied and its reference dropped; the two effects only show something. It has no run: it is done
 * as it starts.
 *
 * <p>The flying warp ({@link #getFlight()} not null, mode InjectedCharacter with a speed) is not
 * started by the runner: the Mega Minion hero's hand-over builds its run with the target it took
 * and that target's last position, and lists it. The run flies the unit as {@code
 * org.crforge.core.battle.unit.WarpRun} describes.
 *
 * <p>Refused as the row is built: a mode other than the relative one or InjectedCharacter, a
 * relative warp with a speed or the flying warp's columns, an InjectedCharacter warp without a
 * speed, an offset away from a tower, a warp that makes the unit untargetable for a step after it,
 * tags, a singleton instant warp, a next action that waits for it, and the gates. As it starts: an
 * owner other than a character, a flying warp started by the runner, which no injected target
 * reaches, and a projectile aimed at the unit, whose drop no reference holds.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the offset by the side, the clamp, the vertical search off water"
            + " and blocked cells, the single position write, the pending damage reset, the route"
            + " and reference cleared. Held by boss_bandit_ability_tower and"
            + " boss_bandit_ability_charges. The flying warp's start, steps and arrival, held by"
            + " ability_hero_mega_minion_vs_musketeer. Not modelled: the two effects. Refused:"
            + " another mode, a relative speed, an instant injected warp, the tower offset, the"
            + " untargetable step, tags, a singleton instant warp, a next action that waits, the"
            + " gates, an owner other than a character, a flying warp the runner starts and a"
            + " projectile aimed at the unit.")
public final class WarpCharacter extends RowAction {

  /**
   * The row's own columns.
   *
   * @param warpX the offset along the width, for side 0
   * @param warpY the offset along the length, for side 0
   * @param resetPath true to empty the unit's route after the warp
   * @param resetTarget true to drop the unit's reference after the warp
   * @param avoidWater true to move a landing on water to an allowed cell of the column
   * @param avoidBlocked true to move a landing on a blocked cell to an allowed cell of the column
   * @param resetPendingDamage true to reset the damage on its way to the unit
   */
  @Builder
  public record Columns(
      int warpX,
      int warpY,
      boolean resetPath,
      boolean resetTarget,
      boolean avoidWater,
      boolean avoidBlocked,
      boolean resetPendingDamage) {}

  /**
   * The flying warp's columns, for a warp to an injected target at a speed.
   *
   * @param speedPerStep the top speed, in units a step
   * @param acceleration what one step adds to the speed or takes off it while braking
   * @param offsetX added to the destination along the width
   * @param offsetY added to the destination along the length, negated for side 1
   * @param forceKeepTarget true to give the unit the warp's target as its reference on arrival, or
   *     drop its reference when the target is gone or rejected
   * @param onWarpEnd the name of the row run on the unit as it arrives, or null
   */
  @Builder
  public record Flight(
      int speedPerStep,
      int acceleration,
      int offsetX,
      int offsetY,
      boolean forceKeepTarget,
      String onWarpEnd) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /** The flying warp's columns, or null for an instant warp. */
  @Getter private final Flight flight;

  /**
   * An instant warp.
   *
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public WarpCharacter(ActionRow row, Columns columns) {
    this(row, columns, null);
  }

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   * @param flight the flying warp's columns, or null for an instant warp
   */
  public WarpCharacter(ActionRow row, Columns columns, Flight flight) {
    super(row);
    this.columns = columns;
    this.flight = flight;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    if (flight != null) {
      // The runner's instance would fly to the zeroed destination fields: nothing is injected.
      throw new UnsupportedOperationException(
          name() + " is started as a flying warp with no injected target, not modelled");
    }
    holder.getOwner().warp(this, holder.passPhase());
    return null;
  }
}
