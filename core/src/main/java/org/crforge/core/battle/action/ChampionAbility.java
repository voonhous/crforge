/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The champion ability controller's row: the action the game's globals name for every player, whose
 * run on the player's king holds a champion's cooldown, charges and button state.
 *
 * <p>The king makes two runs of it as it starts, its first and second champion slot, and lists them
 * after its own; nothing else starts it. Each run steps in the king's run pass and hears every
 * ability its player pays for.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: the row the globals name, its runs made by the king and listed after its own;"
            + " held by the reference battles ability_archer_queen and"
            + " ability_archer_queen_missing_unit.")
public final class ChampionAbility extends RowAction {

  /**
   * Whether a slot that follows one champion may follow another champion its player plays; the
   * loader's default is true.
   */
  @Getter private final boolean allowDynamicReassignments;

  /**
   * @param row the row's shared columns
   * @param allowDynamicReassignments whether a slot may follow another champion
   */
  public ChampionAbility(ActionRow row, boolean allowDynamicReassignments) {
    super(row);
    this.allowDynamicReassignments = allowDynamicReassignments;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().championAbility(this);
  }
}
