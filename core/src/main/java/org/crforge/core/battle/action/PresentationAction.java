package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that only shows something - an effect, a sound, an animation - and changes nothing the
 * simulation reads. It is scheduled and started like any other, so it takes its place in the
 * pending order, and does nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled for the king tower's activation effect: a filter choosing between two effects,"
            + " neither of which the simulation reads. Other presentation rows are not checked.")
public final class PresentationAction implements BattleAction {

  private final String name;

  /**
   * @param name the row's name
   */
  public PresentationAction(String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return null;
  }
}
