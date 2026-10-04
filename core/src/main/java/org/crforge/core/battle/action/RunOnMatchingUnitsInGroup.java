package org.crforge.core.battle.action;

import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that runs its action on every object of the owner's group its filter lets through. It
 * reads only its row and its owner's group chain, and does not last.
 *
 * <p>An owner in no group does nothing. Otherwise the chain is walked from its first object, the
 * owner among them, each object put through the filter for the owner's team and row name - a filter
 * that drops the dead drops an owner that is dying - and every object that passes has the action
 * scheduled on its own holder, built for it, with the owner as its cause, no delay whatever the
 * action's own and not asked to start at once.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: nothing for an owner in no group, the chain from its first object with the owner"
            + " in it, the filter for the owner's team and name, and the action on each match's"
            + " own holder, built for it, the owner as its cause and no delay; held by"
            + " hero_goblins. A row that limits the walk to a range is refused for its column.")
public final class RunOnMatchingUnitsInGroup extends RowAction {

  private final GameObjectFilter filter;
  private final String actionToRun;

  /**
   * @param row the row's shared columns
   * @param filter the filter the group's objects go through
   * @param actionToRun the row of the action run on each match, or null for none
   */
  public RunOnMatchingUnitsInGroup(ActionRow row, GameObjectFilter filter, String actionToRun) {
    super(row);
    this.filter = filter;
    this.actionToRun = actionToRun;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    GroupChain chain = holder.getOwner().groupChain();
    if (!chain.grouped() || actionToRun == null) {
      return null;
    }
    for (GroupChain.Member member : chain.members()) {
      if (filter.matches(member.subject(), chain.team(), chain.rowName())) {
        member.holder().schedule(member.actions().apply(actionToRun), 0, false, holder);
      }
    }
    return null;
  }
}
