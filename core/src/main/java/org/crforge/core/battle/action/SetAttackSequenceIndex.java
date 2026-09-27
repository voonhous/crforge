package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that stores its owner's attack sequence index: only below the length of the owner's
 * order, a longer one dropped with the old one kept, and only while the owner's targeting component
 * is on unless the row asks otherwise. Every reader loads the index when it runs, so the change
 * reaches the next hit. It schedules nothing and does not last.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the native cases of its store and by the evolved Archer's runs: the"
            + " owner, the one-sided bound, the component gate and the next hit reading it.")
public final class SetAttackSequenceIndex extends RowAction {

  private final int index;
  private final boolean evenIfCombatDisabled;

  /**
   * @param row the row's shared columns
   * @param index the index to store
   * @param evenIfCombatDisabled true to store it with the targeting component off too
   */
  public SetAttackSequenceIndex(ActionRow row, int index, boolean evenIfCombatDisabled) {
    super(row);
    this.index = index;
    this.evenIfCombatDisabled = evenIfCombatDisabled;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    holder.getOwner().setAttackSequenceIndex(index, evenIfCombatDisabled);
    return null;
  }
}
