package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that forces its owner's target onto the parent of the area effect that caused it, for a
 * time, with a buff on the owner for as long. Only an area effect with a parent taunts, and only a
 * character is taunted: any other cause or owner does nothing. The owner's run is made and armed at
 * once. Arming, when the owner can attack the forced object and is neither dashing nor winding up a
 * dash: the forced object becomes its reference, its selector is locked from its next pre-hook, and
 * its re-selection waits for the duration; whether or not, the buff is put on it, the forced object
 * its source. When the owner cannot attack it, the run finishes at once. The run's next step ends
 * it: the re-selection wait cleared and, unless the owner is attacking, its reference given up.
 * Every finish removes the buff from the owner.
 *
 * <p>Refused rather than guessed, as the row is built: any column but the valid duration and buff,
 * and a duration longer than one step. As it starts: a taunted building, a taunted unit that rides
 * on another or carries riders, or that is in a pathfinding state, and a forced object that flies.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the perform's gates, the forced object the area effect's parent,"
            + " the arming's valid branch, the buff with the forced object as its source, the one"
            + " step that ends it and the buff removed as it finishes; held by"
            + " goblin_demolisher_knight, where the Goblin Demolisher's cancelling area effect"
            + " taunts it onto itself for one tick. The arming's invalid branch without a buff,"
            + " and the run ending as its forced object leaves, are translated but held by no"
            + " run. Refused: every other column, a taunt longer than one step, a taunted"
            + " building, rider, carrier or pathfinding unit, and a flying forced object.")
public final class Taunt extends RowAction {

  /** How long the owner is taunted when it can attack the forced object, in milliseconds. */
  @Getter private final int validDurationMs;

  /** The buff put on the owner when it can attack the forced object, or null for none. */
  @Getter private final String validTargetBuff;

  /**
   * @param row the row's shared columns
   * @param validDurationMs how long the owner is taunted when it can attack the forced object
   * @param validTargetBuff the buff put on the owner then, or null for none
   */
  public Taunt(ActionRow row, int validDurationMs, String validTargetBuff) {
    super(row);
    this.validDurationMs = validDurationMs;
    this.validTargetBuff = validTargetBuff;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    ActionOwner forced = cause == null ? null : cause.areaEffectParent();
    if (forced == null || holder.getOwner() == null) {
      return null;
    }
    return holder.getOwner().taunt(this, cause, forced, holder.passPhase());
  }
}
