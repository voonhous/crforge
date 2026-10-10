/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that asks whether the owner's group holds another object its filter lets through, and
 * runs one of two actions by the answer. It reads only its row and its owner's group chain, and
 * does not last.
 *
 * <p>An owner in no group does nothing at all, neither action included. Otherwise the chain is
 * walked from its first object, the owner passed by, each other object put through the filter for
 * the owner's team and row name - a filter that drops the dead drops an object whose hit points are
 * gone - and the first that passes is a match. On a match it schedules its action, and with none
 * its action for no match, each on the owner with the owner as its cause, the row's own delay and
 * not asked to start at once; a match with no action does nothing.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: nothing for an owner in no group, the chain from its first object with the owner"
            + " passed by, the filter for the owner's team and name, the first match, and either"
            + " branch scheduled on the owner as its own cause with the row's delay; held by"
            + " hero_goblins. A row that limits the walk to a range is refused for its column.")
public final class RunIfUnitGroupContains extends RowAction {

  private final GameObjectFilter filter;
  private final BattleAction onMatch;
  private final BattleAction onNoMatch;

  /**
   * @param row the row's shared columns
   * @param filter the filter the group's objects go through
   * @param onMatch scheduled on a match, or null
   * @param onNoMatch scheduled when nothing matches, or null
   */
  public RunIfUnitGroupContains(
      ActionRow row, GameObjectFilter filter, BattleAction onMatch, BattleAction onNoMatch) {
    super(row);
    this.filter = filter;
    this.onMatch = onMatch;
    this.onNoMatch = onNoMatch;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    GroupChain chain = holder.getOwner().groupChain();
    if (!chain.grouped()) {
      return null;
    }
    boolean matched = false;
    for (GroupChain.Member member : chain.members()) {
      if (!member.self() && filter.matches(member.subject(), chain.team(), chain.rowName())) {
        matched = true;
        break;
      }
    }
    BattleAction chosen = matched ? onMatch : onNoMatch;
    if (chosen != null) {
      holder.schedule(chosen, ActionHolder.OWN_DELAY, false, holder);
    }
    return null;
  }
}
