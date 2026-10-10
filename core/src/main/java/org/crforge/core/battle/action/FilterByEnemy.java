/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A choice by team: when it starts it schedules one of its two actions on its own holder, with its
 * owner as the cause - the same-team action when the owner and the entity that caused it are on the
 * same team, the enemy action otherwise. A missing action schedules nothing.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the two teams compared, the action chosen and scheduled on the"
            + " owner with the owner as its cause; held by the reference battle"
            + " evo_babydragon_vs_musketeer. Refused: a start with no cause.")
public final class FilterByEnemy extends RowAction {

  private final BattleAction sameTeamAction;
  private final BattleAction enemyAction;

  /**
   * @param row the row's shared columns
   * @param sameTeamAction the action for an owner on its cause's team, or null for none
   * @param enemyAction the action for an owner on the other team, or null for none
   */
  public FilterByEnemy(ActionRow row, BattleAction sameTeamAction, BattleAction enemyAction) {
    super(row);
    this.sameTeamAction = sameTeamAction;
    this.enemyAction = enemyAction;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator == null) {
      throw new UnsupportedOperationException(name() + " starts with no cause, not modelled");
    }
    boolean same = holder.getOwner().actionTeam() == instigator.getOwner().actionTeam();
    BattleAction chosen = same ? sameTeamAction : enemyAction;
    holder
        .getOwner()
        .filteredByTeam(name(), instigator.getOwner(), same, chosen == null ? null : chosen.name());
    if (chosen != null) {
      holder.schedule(chosen, ActionHolder.OWN_DELAY, false, holder);
    }
    return null;
  }
}
