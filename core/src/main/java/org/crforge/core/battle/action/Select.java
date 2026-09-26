package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that schedules one of its parts the moment it is scheduled: with per-part conditions,
 * the first whose value is not zero; otherwise the part its condition's value names, taken modulo
 * the list, and none for a negative value. Its own start does nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases: the per-part conditions taking precedence, the"
            + " first true one chosen, none when none is true, the condition modulo the list and"
            + " none for a negative one. Supplied: a row without a condition chooses the first"
            + " part. Not modelled: whether its start gate stops the choice, and the context the"
            + " chosen part inherits.")
public final class Select extends RowAction {

  private final List<BattleAction> parts;
  private final IntSupplier condition;
  private final List<IntSupplier> partConditions;

  /**
   * @param row the row's shared columns
   * @param parts the actions it chooses between
   * @param condition the index of the part to choose, or null for 0
   * @param partConditions a condition per part, or null for none
   */
  public Select(
      ActionRow row,
      List<BattleAction> parts,
      IntSupplier condition,
      List<IntSupplier> partConditions) {
    super(row);
    this.parts = List.copyOf(parts);
    this.condition = condition;
    this.partConditions = partConditions == null ? null : List.copyOf(partConditions);
  }

  @Override
  public void scheduled(
      ActionHolder holder, int delayMs, boolean immediate, ActionHolder instigator) {
    BattleAction chosen = choose();
    if (chosen != null) {
      holder.schedule(chosen, delayMs, immediate, instigator);
    }
  }

  private BattleAction choose() {
    if (parts.isEmpty()) {
      return null;
    }
    if (partConditions != null && !partConditions.isEmpty()) {
      for (int i = 0; i < partConditions.size() && i < parts.size(); i++) {
        if (partConditions.get(i).getAsInt() != 0) {
          return parts.get(i);
        }
      }
      return null;
    }
    int index = condition == null ? 0 : condition.getAsInt();
    return index < 0 ? null : parts.get(index % parts.size());
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return null;
  }
}
