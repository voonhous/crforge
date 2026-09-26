package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A row of a class that changes nothing the simulation reads: one that overrides none of the three
 * places where the action runtime reaches a class, or one that only shows something. It is
 * scheduled and started like any other row, with its shared columns, and does nothing.
 *
 * <p>A lasting row keeps a run while it is listed. The run never finishes by itself: only its stop
 * gate, or its owner leaving, ends it. While listed it sets the row's tags and holds a singleton's
 * second start, as any run does. A row without a run never sets its tags.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled for the four classes that override none of the three (the animator layer, the"
            + " health bar part, the visual action group and the champion button animator), for"
            + " the effect-playing row, which lasts when its flags loop it or link its life to the"
            + " run's, and for the forced animation.")
public final class InertAction extends RowAction {

  private final boolean lasting;

  /**
   * A row with no run.
   *
   * @param row the row's shared columns
   */
  public InertAction(ActionRow row) {
    this(row, false);
  }

  /**
   * @param row the row's shared columns
   * @param lasting true for a row that keeps a run, which never finishes by itself
   */
  public InertAction(ActionRow row, boolean lasting) {
    super(row);
    this.lasting = lasting;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    if (!lasting) {
      return null;
    }
    return new ActionInstance(this) {
      @Override
      protected void update(ActionHolder h) {
        // Shows something; nothing the simulation reads changes, and the run never ends itself.
      }
    };
  }
}
