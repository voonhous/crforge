/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.FilterSubject;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.filter.ObjectCensus;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that checks whether objects exist and runs one of two actions by the outcome. It reads
 * only its row and its owner, and does not last.
 *
 * <p>Every live object of the owner's battle, in id order and the owner among them, goes through
 * the row's filter for the owner's team and row name. With no filter the check does nothing at all.
 * Then one filtered object whose row is excluded vetoes the whole check. Otherwise, with rows to
 * match, the filtered objects whose row is listed are counted, each once, and the check matches as
 * soon as the count reaches the number needed - tested after every object, so a number of 0 or less
 * matches on the first object; without rows to match, the filtered objects themselves must number
 * at least the number needed, and at least one. No filtered object is never a match. Rows are
 * compared by their global ids, and a name the data has no row for was dropped as the row was read.
 *
 * <p>On a match it schedules its action to run, and otherwise its action to run when nothing
 * matches, each on the owner with the owner as its cause, the row's own delay and not asked to
 * start at once: inside a pending pass, with no delay, it runs at once.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases of its perform and by BattleRunIfExistsTest: the"
            + " live list through the filter for the owner's team and name, the veto, the count"
            + " after every object, the count without names, the empty list, the missing filter,"
            + " and the branch scheduled on the owner as its own cause.")
public final class RunIfGameObjectExists extends RowAction {

  private final GameObjectFilter filter;
  private final List<Integer> matchIds;
  private final List<Integer> excludeIds;
  private final int needed;
  private final BattleAction onMatch;
  private final BattleAction onNoMatch;

  /**
   * @param row the row's shared columns
   * @param filter the filter the objects go through, or null for none
   * @param matchIds the global ids of the rows to count
   * @param excludeIds the global ids of the rows that veto the check
   * @param needed how many matching objects make a match
   * @param onMatch scheduled on a match, or null
   * @param onNoMatch scheduled otherwise, or null
   */
  public RunIfGameObjectExists(
      ActionRow row,
      GameObjectFilter filter,
      List<Integer> matchIds,
      List<Integer> excludeIds,
      int needed,
      BattleAction onMatch,
      BattleAction onNoMatch) {
    super(row);
    this.filter = filter;
    this.matchIds = List.copyOf(matchIds);
    this.excludeIds = List.copyOf(excludeIds);
    this.needed = needed;
    this.onMatch = onMatch;
    this.onNoMatch = onNoMatch;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (filter == null) {
      return null;
    }
    ObjectCensus census = holder.getOwner().census();
    List<FilterSubject> found =
        census.objects().stream()
            .filter(object -> filter.matches(object, census.team(), census.rowName()))
            .toList();
    BattleAction chosen = matches(found) ? onMatch : onNoMatch;
    if (chosen != null) {
      holder.schedule(chosen, ActionHolder.OWN_DELAY, false, holder);
    }
    return null;
  }

  /** Whether the filtered objects make a match. */
  private boolean matches(List<FilterSubject> found) {
    if (found.isEmpty()) {
      return false;
    }
    if (!excludeIds.isEmpty()) {
      for (FilterSubject object : found) {
        if (excludeIds.contains(object.rowGlobalId())) {
          return false;
        }
      }
    }
    if (matchIds.isEmpty()) {
      return found.size() >= Math.max(needed, 1);
    }
    int count = 0;
    for (FilterSubject object : found) {
      if (matchIds.contains(object.rowGlobalId())) {
        count++;
      }
      if (count >= needed) {
        return true;
      }
    }
    return false;
  }
}
