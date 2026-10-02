package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that listens for its owner's side playing a card, as Goblinstein's monster does: its
 * run is listed for as long as its owner lives and does nothing in its steps.
 *
 * <p>The listener is made in its owner's pending pass, after the play that made the owner was sent,
 * so it never hears that play. A later card play of the owner's side that it would hear runs its
 * activation action when the card is in its card group; which cards a group holds is not among the
 * battle's tables, so any later card play of its side while it is listed is refused.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the run listed from the owner's pending pass, after the play that made it, so"
            + " that play is not heard; held by goblinstein_tower. Refused: any later card play of"
            + " the owner's side while the run is listed, as the card group it tests is not read.")
public final class CardDeployListener extends RowAction {

  /** The card group whose plays the listener answers. */
  @Getter private final String cardGroup;

  /**
   * @param row the row's shared columns
   * @param cardGroup the card group whose plays it answers
   */
  public CardDeployListener(ActionRow row, String cardGroup) {
    super(row);
    this.cardGroup = cardGroup;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  /** The listening run: listed, and nothing in its steps. */
  private final class Run extends ActionInstance {

    private Run() {
      super(CardDeployListener.this);
    }

    @Override
    protected void update(ActionHolder holder) {}
  }
}
