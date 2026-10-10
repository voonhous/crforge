/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that checks what caused it and runs one of two actions by the outcome. It reads only
 * its row, its cause and its owner, and does not last.
 *
 * <p>Without a cause it does nothing at all, not even its action to run when nothing matches. With
 * one and an object filter, the cause goes through the filter first, asked for the owner's team and
 * row and with whether the cause is the owner itself; a cause the filter refuses does not match.
 * Then the cause's row is compared by its global id with the character and building rows the row
 * names: without names anything matches, and a cause of no such row, such as a projectile, matches
 * none. A name the data has no row for was dropped as the row was read. Without a filter, whether
 * the cause is still alive is not asked: a dead cause matches as a living one does.
 *
 * <p>On a match it schedules its action to run, and otherwise its action to run when nothing
 * matches, each on the owner with the owner as its cause, the row's own delay and not asked to
 * start at once: inside a pending pass, with no delay, it runs at once.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled by the recorded cases of its perform and held by the reference battles"
            + " ability_boss_bandit and card_BossBandit_until_stop: a killer's check of what it"
            + " killed and a killed unit's check of its killer, the branch scheduled on the owner"
            + " as its own cause. The object filter (the JumpHack check) is read from the build's"
            + " perform, which passes the cause, the owner's team and the owner's identity to the"
            + " filter test before the names.")
public final class RunIfInstigatorMatches extends RowAction {

  private final GameObjectFilter filter;
  private final List<Integer> matchIds;
  private final BattleAction onMatch;
  private final BattleAction onNoMatch;

  /**
   * @param row the row's shared columns
   * @param filter the filter the cause must pass, or null for none
   * @param matchIds the global ids of the rows a cause must have to match; empty for any
   * @param onMatch scheduled on a match, or null
   * @param onNoMatch scheduled otherwise, or null
   */
  public RunIfInstigatorMatches(
      ActionRow row,
      GameObjectFilter filter,
      List<Integer> matchIds,
      BattleAction onMatch,
      BattleAction onNoMatch) {
    super(row);
    this.filter = filter;
    this.matchIds = List.copyOf(matchIds);
    this.onMatch = onMatch;
    this.onNoMatch = onNoMatch;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator == null) {
      return null;
    }
    ActionOwner cause = instigator.getOwner();
    ActionOwner owner = holder.getOwner();
    boolean matches =
        (filter == null
                || filter.matches(
                    cause.actionFilterSubject(),
                    owner.actionTeam(),
                    owner.actionRowName(),
                    cause == owner))
            && (matchIds.isEmpty() || matchIds.contains(cause.actionUnitGlobalId()));
    BattleAction chosen = matches ? onMatch : onNoMatch;
    if (chosen != null) {
      holder.schedule(chosen, ActionHolder.OWN_DELAY, false, holder);
    }
    owner.instigatorChecked(name(), cause, chosen == null ? null : chosen.name());
    return null;
  }
}
