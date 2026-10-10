/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that empties its owner's route, as the evolved Royal Hog's landing does a step after it
 * lands: an owner with a movement component loses its route and the bit that says the route leads
 * away, so its next movement pass plans a new one; an owner without one is left alone. It reads no
 * column of its own and does not last.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the owner's movement component, when it has one, has its route"
            + " emptied, the same reset a landing knock makes; held by"
            + " evo_royalhogs_vs_musketeer. Refused: an owner with a movement component that is"
            + " not a character.")
public final class ResetPath extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public ResetPath(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    holder.getOwner().resetPath(this);
    return null;
  }
}
