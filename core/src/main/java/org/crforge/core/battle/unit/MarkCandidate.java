package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * A character or building a mark's resolver collects, read live: its id, row, position and action
 * holder, and the maximum hit points plus maximum shield the lowest-maximum strategy compares.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The maximum is the hit points' maximum at a growth of 100 percent plus the shield's"
            + " maximum; no unit that grows is modelled. An object without hit points is refused"
            + " as the strategy reads it.")
final class MarkCandidate implements SetIndicatorOnTarget.Candidate {

  private final WorldEntity entity;
  private final String action;

  /**
   * @param entity the object
   * @param action the name of the action that resolves it, for a refusal
   */
  MarkCandidate(WorldEntity entity, String action) {
    this.entity = entity;
    this.action = action;
  }

  @Override
  public int id() {
    return entity.getId();
  }

  @Override
  public String rowName() {
    return entity.getData().name();
  }

  @Override
  public int maxHitPoints() {
    HitPoints hitPoints = entity.getHitPoints();
    if (hitPoints == null) {
      throw new UnsupportedOperationException(
          action + " compares the maximum hit points of " + rowName() + ", which has none");
    }
    return hitPoints.getMaximum() + hitPoints.getShieldMaximum();
  }

  @Override
  public int x() {
    return entity.getView().getX();
  }

  @Override
  public int y() {
    return entity.getView().getY();
  }

  @Override
  public ActionHolder holder() {
    return entity.actionHolder();
  }
}
