package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A Clone's action on a unit its hit reached: the perform tests the unit again - no unit a Clone
 * passes by, a living one riding nothing - then schedules its cloned action on the unit, with the
 * action's cause as its own, which inside a pending pass runs at once, and then makes the clone. It
 * does not ask whether the unit is a clone: the Clone's area filter has dropped clones already, and
 * a clone reached otherwise is refused (see {@link ActionOwner#mayBeCloned}). The clone and the
 * unit move apart for the row's clone duration. It does not last: the moves are runs of their own.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the reference battles card_Clone, spell_clone_into_push and"
            + " card_item_clone_goblins_minions: the perform's tests, the cloned action scheduled"
            + " first and the creator after it. Presentation only: the deploy animation it names"
            + " and the card its statistics count.")
public final class Clone extends RowAction {

  /** The clone duration the loader stores for a row without one. */
  public static final int DEFAULT_CLONE_DURATION_MS = 500;

  private final BattleAction onClonedAction;

  /** How long the clone and the unit move apart. */
  @Getter private final int cloneDurationMs;

  /**
   * @param row the row's shared columns
   * @param onClonedAction scheduled on the unit before its clone is made, or null
   * @param cloneDurationMs how long the clone and the unit move apart
   */
  public Clone(ActionRow row, BattleAction onClonedAction, int cloneDurationMs) {
    super(row);
    this.onClonedAction = onClonedAction;
    this.cloneDurationMs = cloneDurationMs;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner owner = holder.getOwner();
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    if (owner == null || !owner.mayBeCloned(cause)) {
      return null;
    }
    if (onClonedAction != null) {
      holder.schedule(onClonedAction, ActionHolder.OWN_DELAY, false, instigator);
    }
    owner.makeClone(cause, this);
    return null;
  }
}
