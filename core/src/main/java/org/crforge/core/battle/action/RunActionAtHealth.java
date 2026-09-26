package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * An action that lasts and runs one action per health threshold as its owner's hit points fall.
 * Each threshold is a whole percentage of the owner's own maximum, reached when that share is at
 * least the hit points, so 50 fires at exactly half. One step that falls past several fires them
 * all in list order; the index only moves forward, so healing does not re-arm an entry, and the run
 * ends once every entry has fired. The run ends at once unless both lists are non-empty and as long
 * as each other and the owner has hit points.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases: the start conditions, the threshold at or below"
            + " its share, several fired in one step, the index that never goes back and the end"
            + " when the last has fired.")
public final class RunActionAtHealth extends RowAction {

  private final List<Integer> healthPercentages;
  private final List<BattleAction> actions;

  /**
   * @param row the row's shared columns
   * @param healthPercentages the thresholds, in whole percent of the maximum
   * @param actions the action each threshold runs
   */
  public RunActionAtHealth(
      ActionRow row, List<Integer> healthPercentages, List<BattleAction> actions) {
    super(row);
    this.healthPercentages = List.copyOf(healthPercentages);
    this.actions = List.copyOf(actions);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ActionOwner owner = holder.getOwner();
    HitPoints hp = owner == null ? null : owner.actionHitPoints();
    ActionInstance run =
        new ActionInstance(this) {
          private int index;

          @Override
          protected void update(ActionHolder h) {
            while (index < healthPercentages.size()
                && healthPercentages.get(index) * hp.getMaximum() >= hp.getHitPoints() * 100) {
              h.schedule(actions.get(index), ActionHolder.OWN_DELAY);
              index++;
            }
            if (index >= healthPercentages.size()) {
              finish();
            }
          }
        };
    if (healthPercentages.isEmpty() || actions.size() != healthPercentages.size() || hp == null) {
      run.finish();
    }
    return run;
  }
}
