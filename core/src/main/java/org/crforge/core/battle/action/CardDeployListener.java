package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.IntSupplier;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that listens for its owner's side playing a card, as Goblinstein's monster does: its
 * run is listed for as long as its owner lives and does nothing in its steps.
 *
 * <p>The listener is made in its owner's pending pass, after the play that made the owner was sent,
 * so it never hears that play. A later card play of the owner's side that it hears tests the card -
 * the card the play put down, a Mirror's repeated card or an evolved or hero play's row, with
 * EvaluateDeployedCard, else the card played - against its card group's playable cards; a listener
 * with no group takes every card. When the elixir the run has counted reaches the row's cost before
 * the play, the activation action is scheduled on the owner, the owner its cause, and the cost
 * taken off the count; below it, the played card's cost is added to the count. The effects it shows
 * are presentation.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the run listed from the owner's pending pass, after the play that"
            + " made it, so that play is not heard; the side test, the card tested, the group's"
            + " playable cards, the elixir count and the activation action; held by the reference"
            + " battles ability_goblinstein and card_Goblinstein. A count below the cost, which"
            + " no listener of a modelled card reaches, is held by a unit test only; a Mirror's"
            + " repeated card tested in place of the Mirror, and an evolved or hero play's row in"
            + " place of its deck card, are translated but held by no run. Refused: a variant"
            + " card's play heard by a listener of its side.")
public final class CardDeployListener extends RowAction {

  /** The card group whose plays the listener answers, empty for every card. */
  @Getter private final String cardGroup;

  /** The group's playable cards, or null for no group. */
  private final List<String> playableCards;

  /** True to test the card the play put down rather than the card played. */
  @Getter private final boolean evaluateDeployedCard;

  /** The elixir the count must reach before a play activates. */
  @Getter private final int elixirCost;

  /** The action a play schedules on the owner once the count reaches the cost, or null. */
  @Getter private final String onActivateAction;

  /**
   * @param row the row's shared columns
   * @param cardGroup the card group whose plays it answers, empty for every card
   * @param playableCards the group's playable cards, or null for no group
   * @param evaluateDeployedCard true to test the card the play put down
   * @param elixirCost the elixir the count must reach before a play activates
   * @param onActivateAction the action an activating play schedules on the owner, or null
   */
  public CardDeployListener(
      ActionRow row,
      String cardGroup,
      List<String> playableCards,
      boolean evaluateDeployedCard,
      int elixirCost,
      String onActivateAction) {
    super(row);
    this.cardGroup = cardGroup;
    this.playableCards = playableCards == null ? null : List.copyOf(playableCards);
    this.evaluateDeployedCard = evaluateDeployedCard;
    this.elixirCost = elixirCost;
    this.onActivateAction = onActivateAction;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  /** The listening run: listed, nothing in its steps, and the elixir it has counted. */
  public final class Run extends ActionInstance {

    /** The elixir the run has counted from the plays it heard. */
    @Getter private int total;

    private Run() {
      super(CardDeployListener.this);
    }

    @Override
    protected void update(ActionHolder holder) {}

    /**
     * Hears a card play.
     *
     * @param ownerSide the owner's side
     * @param side the side that played
     * @param deployed the card the play put down: a Mirror's repeated card
     * @param played the card played: the Mirror
     * @param deployedCost the cost of the card the play put down, asked only when it is counted
     * @return the action to schedule on the owner, or null for none
     */
    public String hear(
        int ownerSide, int side, String deployed, String played, IntSupplier deployedCost) {
      if ((side & 1) != (ownerSide & 1)) {
        return null;
      }
      String card = evaluateDeployedCard ? deployed : played;
      if (playableCards != null && !playableCards.contains(card)) {
        return null;
      }
      if (total >= elixirCost) {
        total -= elixirCost;
        return onActivateAction;
      }
      total += deployedCost.getAsInt();
      return null;
    }
  }
}
