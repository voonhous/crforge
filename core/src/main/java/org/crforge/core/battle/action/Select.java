package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that schedules one of its parts the moment it is scheduled, after it has itself been
 * queued or, inside a pending pass, started.
 *
 * <p>Its start gate is asked first: a false gate chooses nothing and evaluates nothing. Then, with
 * per-part conditions, the first part whose condition is not zero, or, when every one is zero and
 * there is a part past the last condition, that part; otherwise the part its condition's value
 * names, taken modulo the list, and none for a negative value or no condition at all. So a
 * condition that draws from the battle's random source draws as the select is scheduled, not when
 * it runs. The part is scheduled with no delay - neither the select's nor its own - and the
 * select's cause, without being asked to start at once. Its own start does nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded cases and the native runs of its hook: the start gate"
            + " before the choice, the per-part conditions taking precedence, the first true one"
            + " chosen, the part past the last condition when none is true, the condition modulo"
            + " the list, none for a negative one or none at all, and the part scheduled with no"
            + " delay and the select's cause; held by gift_select's draws at scheduling. Not"
            + " modelled: the select's delay passed on to the part, which a row asks for with a"
            + " column that is refused, and the context the chosen part inherits.")
public final class Select extends RowAction {

  private final List<BattleAction> parts;
  private final IntSupplier condition;
  private final List<IntSupplier> partConditions;

  /**
   * @param row the row's shared columns
   * @param parts the actions it chooses between
   * @param condition the index of the part to choose, or null for none, which chooses nothing
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
    if (executeIf() != null && executeIf().getAsInt() == 0) {
      return;
    }
    BattleAction chosen = choose();
    if (chosen != null) {
      // No delay, the select's own or the part's: a delay of 0 is not the row's own.
      holder.schedule(chosen, 0, false, instigator);
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
      // Every condition is zero: the part just past the last one, when there is one, is the else.
      return partConditions.size() < parts.size() ? parts.get(partConditions.size()) : null;
    }
    if (condition == null) {
      return null;
    }
    int index = condition.getAsInt();
    return index < 0 ? null : parts.get(index % parts.size());
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return null;
  }
}
