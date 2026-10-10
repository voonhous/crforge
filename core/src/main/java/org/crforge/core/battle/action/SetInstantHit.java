/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that raises its owner's instant-hit byte on the owner's targeting component, whether
 * that component is switched on or not. The byte stays set until the owner's next attack-timer
 * advance, which then rounds the attack time up to the next whole hit in place of stepping it, so
 * the hit lands on that very attack visit, and clears it; switching the component off leaves it
 * set. The row's start gate (ExecuteIfTrue) decides whether it runs at all. It has no columns of
 * its own, schedules nothing and does not last.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the byte set on the owner's targeting component whatever its switch, read and"
            + " cleared only by the next attack-timer advance's round-up. Held by"
            + " ability_hero_wizard up to the activation's set; the round-up it leads to is held"
            + " only by the tests, since the reference battle swaps the hero to its flying row"
            + " before its next attack visit. Not reached by a recorded case: the Mega Minion"
            + " hero's row, whose gate the battle does not answer yet.")
public final class SetInstantHit extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public SetInstantHit(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    holder.getOwner().setInstantHit();
    return null;
  }
}
