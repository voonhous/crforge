package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A row of a class that overrides none of the three places where the action runtime reaches a class
 * - when it is scheduled, when it performs and when its run is made - so it cannot change anything
 * the simulation reads. It is scheduled and started like any other row, with its shared columns,
 * and does nothing.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled for the four classes that override none of the three: the animator layer, the"
            + " health bar part, the visual action group and the champion button animator.")
public final class InertAction extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public InertAction(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return null;
  }
}
