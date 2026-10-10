/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that drops its owner's target, as the hero Barbarian Barrel's roll does as it ends: an
 * owner with a targeting component loses its reference and the byte that keeps a reference with
 * pending damage, so its next targeting visit looks for a new one; an owner without one is left
 * alone. It reads no column of its own and does not last.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the owner's targeting component, when it has one, has its"
            + " reference and its keep byte cleared, the same reset a warp makes; held by"
            + " ability_hero_barb_log. Refused: an owner with a targeting component that is not a"
            + " character.")
public final class ResetTarget extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public ResetTarget(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    holder.getOwner().resetTarget(this);
    return null;
  }
}
