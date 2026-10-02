package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that moves its unit at once by a fixed offset toward its own side, as the Boss Bandit's
 * warp does.
 *
 * <p>The destination is the unit's position plus the offset, both axes negated for side 1, so a
 * negative length offset carries either side's unit back toward its own king; it is clamped into
 * the arena. A landing cell that is water, or that is the blocked value alone, is replaced by the
 * nearest allowed cell of the same column within four rows, the nearer half-row neighbour tried
 * first at each distance, at that cell's centre; with none, the starting row's centre. The unit is
 * placed there in one write. Then its pending damage is reset, its route emptied and its reference
 * dropped; the two effects only show something.
 *
 * <p>It has no run: it is done as it starts. Refused as the row is built: a mode other than the
 * relative one, a speed, which would make a run that flies, the warp's other columns, tags, a
 * singleton, a next action that waits for it, and the gates. As it starts: an owner other than a
 * character, and a projectile aimed at the unit, whose drop no reference holds.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the offset by the side, the clamp, the vertical search off water"
            + " and blocked cells, the single position write, the pending damage reset, the route"
            + " and reference cleared. Held by boss_bandit_ability_tower and"
            + " boss_bandit_ability_charges. Not modelled: the two effects. Refused: another mode,"
            + " a speed, the warp's other columns, tags, a singleton, a next action that waits,"
            + " the gates, an owner other than a character and a projectile aimed at the unit.")
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

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public WarpCharacter(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    holder.getOwner().warp(this, holder.passPhase());
    return null;
  }
}
