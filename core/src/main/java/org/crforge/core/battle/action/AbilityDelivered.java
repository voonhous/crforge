/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that marks its owner's ability as delivered, as the newer hero rows do once the ability
 * has done its work (the hero Ice Wizard's cube spawned, the hero Golden Knight's chain or the hero
 * Valkyrie's whirlwind started): a character owner drops the cost of its ability's last use that it
 * carried for an ability row with RefundUntilDelivered, so its leaving no longer gives that cost
 * back. Any other owner is left alone. It reads no column of its own and does not last; its next
 * action, if any, follows as for any action with no run.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Traced on client 16.635.1 and held by no reference yet: no run is made; the row's start"
            + " clears the carried cost on a character owner and leaves any other owner alone.")
public final class AbilityDelivered extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public AbilityDelivered(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ActionOwner owner = holder.getOwner();
    if (owner != null) {
      owner.abilityDelivered();
    }
    return null;
  }
}
