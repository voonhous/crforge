package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The Skeleton Barrel's pop action, its starting action. It has no perform of its own, but its
 * class asks for a run: the run is started, listed and stepped every tick while its owner stands,
 * and does nothing; it never finishes by itself. The balloons it pops as the hit points fall, their
 * frames and their effects reach only the run's view object, so nothing the battle reads changes.
 *
 * <p>A singleton row's second start re-triggers the run, which drops containers as area effects;
 * such a row is refused as it is built, with the columns that start reads.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled for the plain row, whose run is stepped doing nothing, held by"
            + " skeleton_barrel_tower and skeleton_barrel_shot_down. Not modelled: a singleton"
            + " row's re-trigger and the containers it drops, refused as the row is built.")
public final class PopBalloons extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public PopBalloons(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new ActionInstance(this) {
      @Override
      protected void update(ActionHolder h) {
        // Its step is the base one, which does nothing; only its view object reads the balloons.
      }
    };
  }
}
