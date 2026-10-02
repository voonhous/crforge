package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The Goblin Cage's shake, its starting action. It has no perform of its own, but its class asks
 * for a run: the run is started, listed and stepped every tick while its owner stands, and does
 * nothing; it never finishes by itself. Whether the owner holds a troop, and so which frames it
 * plays and at what priority, is read by its client view alone, so nothing the battle reads
 * changes.
 *
 * <p>The columns that would give the run something to do - tags to set, a stop gate, a singleton's
 * second start and a chained action - are refused as the row is built.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled for the plain row, whose run is stepped doing nothing, held by goblin_cage_knight"
            + " and goblin_cage_lifetime. Not modelled: tags, a stop gate, a singleton or a"
            + " chained action on such a row, refused as the row is built.")
public final class PlayAnimationIfHasTarget extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public PlayAnimationIfHasTarget(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new ActionInstance(this) {
      @Override
      protected void update(ActionHolder h) {
        // Its step is the base one, which does nothing; only its view object reads the target.
      }
    };
  }
}
