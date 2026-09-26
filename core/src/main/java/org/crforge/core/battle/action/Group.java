package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that schedules its parts the moment it is scheduled, in list order, each at the group's
 * delay plus its own entry of the delay list - a part past the end of that list at the group's
 * delay alone. Its own start does nothing, and a false start gate stops it scheduling anything.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the parts scheduled when the group is scheduled,"
            + " in order, at the group's delay plus their own, none past a false start gate, and"
            + " the group's own empty start. Not modelled: the context the parts inherit, drop or"
            + " create.")
public final class Group extends RowAction {

  private final List<BattleAction> parts;
  private final List<Integer> partDelaysMs;

  /**
   * @param row the row's shared columns
   * @param parts the actions the group schedules
   * @param partDelaysMs each part's delay on top of the group's, in milliseconds
   */
  public Group(ActionRow row, List<BattleAction> parts, List<Integer> partDelaysMs) {
    super(row);
    this.parts = List.copyOf(parts);
    this.partDelaysMs = List.copyOf(partDelaysMs);
  }

  @Override
  public void scheduled(
      ActionHolder holder, int delayMs, boolean immediate, ActionHolder instigator) {
    if (executeIf() != null && executeIf().getAsInt() == 0) {
      return;
    }
    for (int i = 0; i < parts.size(); i++) {
      int own = i < partDelaysMs.size() ? partDelaysMs.get(i) : 0;
      holder.schedule(parts.get(i), delayMs + own, immediate, instigator);
    }
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return null;
  }
}
