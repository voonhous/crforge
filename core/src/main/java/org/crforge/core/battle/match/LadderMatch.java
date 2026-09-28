package org.crforge.core.battle.match;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleMode;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * A Ladder match between two players: the battle's clock, each king's elixir and hand, and the
 * gates a card play passes before it is placed.
 *
 * <p>The match is set up once the towers stand, side 0 first: each king starts with the timeline's
 * starting elixir, and its deck is shuffled into its battle order with one draw of the battle's
 * random source. Each step the update advances the timeline to the battle tick before the entities
 * are ticked; in their post-hook pass each king refills its hand and then regenerates its elixir,
 * at the rate and cooldown the timeline gives for that tick.
 *
 * <p>A card play first passes the match's gates, in order: the king must be alive (code 8), the
 * card must be in one of the hand's four slots (code 9), and the whole elixir must cover its cost
 * (code 0xd). A refused play changes nothing. One that places or casts takes its cost and moves the
 * card to the back of the queue before its units are created or its spell cast.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by match_elixir_150s: the setup in side order with the shuffle's draw,"
            + " the update's advance before the entity tick, the kings' refill and regeneration in"
            + " their post-hook, the gates 9 and 0xd and the spend and cycle before the placement's"
            + " units. Not modelled yet, and refused: the end of a match - a king's death, the time"
            + " running out, overtime's first crown - with the crowns it is decided by, the end"
            + " handler, the end timer, the fallen king's circle and the tiebreaker; the Mirror.")
public final class LadderMatch implements BattleMode {

  /** The game mode row the match is played under. */
  public static final String GAME_MODE = "Ladder";

  /** The bound of the battle-source draw that seeds a deck's shuffle. */
  private static final int SHUFFLE_BOUND = 0x0fffffff;

  /** A refused play: the king is dead. */
  public static final int KING_DEAD = 8;

  /** A refused play: the card is not in one of the hand's four slots. */
  public static final int NOT_IN_HAND = 9;

  /** A refused play: the whole elixir does not cover the card's cost. */
  public static final int NOT_ENOUGH_ELIXIR = 0xd;

  private final BattleWorld world;

  @Getter private final Timeline timeline;

  private final int maxMana;

  private final List<MatchSide> sides = new ArrayList<>();

  /** The battle-source draw each side's shuffle was seeded with, before the player's word. */
  private final int[] shuffleDraws = new int[2];

  /**
   * Sets a match up on a battle whose towers stand.
   *
   * @param world the battle's world, whose random source the shuffles draw from
   * @param records the battle's records
   * @param decks each side's deck, by card row name
   * @param playerWords each side's word, added to its shuffle's draw
   */
  public LadderMatch(
      BattleWorld world, BattleRecords records, List<List<String>> decks, int[] playerWords) {
    checkArgument(decks.size() == 2 && playerWords.length == 2, () -> "a match has two players");
    this.world = world;
    this.timeline = new Timeline(records.gameModeTimeline(GAME_MODE));
    this.maxMana = records.globalNumber("MAX_MANA");
    for (int side = 0; side < 2; side++) {
      List<MatchCard> deck = new ArrayList<>();
      for (String name : decks.get(side)) {
        deck.add(records.matchCard(name));
      }
      MatchSide matchSide = new MatchSide(deck, timeline.row().startingElixir());
      int draw = world.getRandom().next(SHUFFLE_BOUND);
      shuffleDraws[side] = draw;
      matchSide.getHand().deal(DeckShuffle.order(deck, draw + playerWords[side]));
      sides.add(matchSide);
    }
  }

  /**
   * One side of the match.
   *
   * @param side 0 or 1
   */
  public MatchSide side(int side) {
    return sides.get(side);
  }

  /**
   * The battle-source draw a side's shuffle was seeded with.
   *
   * @param side 0 or 1
   */
  public int shuffleDraw(int side) {
    return shuffleDraws[side];
  }

  /**
   * Whether the match is over and stopped. The end of a match is not modelled yet: a match that
   * would be over is refused here, at the head of the step that would see it.
   */
  @Override
  public boolean isOver() {
    if (decided()) {
      throw new UnsupportedOperationException(
          "the match is over - a king fell, overtime saw a crown or the time ran out - and the"
              + " end of a match is not modelled");
    }
    return false;
  }

  /** The update: the crowns-equal byte, then the timeline advanced to the battle tick. */
  @Override
  public boolean update(Battle battle) {
    timeline.setCrownsEqual(crowns(0) == crowns(1));
    timeline.advance(battle.getTick());
    return true;
  }

  /**
   * A king's visit in its post-hook: its side refills its hand and regenerates its elixir.
   *
   * @param king the king tower
   */
  public void kingVisit(TowerEntity king) {
    sides.get(king.side() & 1).visit(timeline, maxMana);
  }

  /**
   * The crowns a side has taken: 3 when the other side's king is dead, else one for each princess
   * tower the other side has lost, up to 2. A fallen princess tower counts from the cleanup that
   * removes it from the holder.
   *
   * @param side 0 or 1
   */
  public int crowns(int side) {
    int other = 1 - side;
    if (!kingAlive(other)) {
      return 3;
    }
    int princessTowers = 0;
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof TowerEntity tower
          && tower.getData().summonerTower()
          && tower.side() == other) {
        princessTowers++;
      }
    }
    return 2 - Math.min(princessTowers, 2);
  }

  /** Whether a side's king still has hit points; a king is never removed from the holder. */
  private boolean kingAlive(int side) {
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.getData().king() && tower.side() == side) {
        return HitPoints.alive(tower.getHitPoints());
      }
    }
    throw new IllegalStateException("side " + side + " has no king tower");
  }

  /** Whether the match is decided: a king is dead, overtime has seen a crown, or time is up. */
  private boolean decided() {
    if (!kingAlive(0) || !kingAlive(1)) {
      return true;
    }
    if (timeline.overtime() && crowns(0) != crowns(1)) {
      return true;
    }
    return timeline.timeUp();
  }

  /**
   * The deck index a play of a card stands for: the first slot of the hand holding that card, else
   * the card's first index in the deck.
   *
   * @param side the playing side
   * @param card the card row's name
   */
  public int deckIndex(int side, String card) {
    MatchSide matchSide = sides.get(side);
    List<MatchCard> deck = matchSide.deck();
    for (int index : matchSide.getHand().slots()) {
      if (index != Hand.EMPTY && deck.get(index).name().equals(card)) {
        return index;
      }
    }
    for (int index = 0; index < deck.size(); index++) {
      if (deck.get(index).name().equals(card)) {
        return index;
      }
    }
    throw new IllegalArgumentException(card + " is not in side " + side + "'s deck");
  }

  /**
   * The match's gates for a play, in order: the king alive, the card in the hand, the elixir.
   *
   * @param side the playing side
   * @param index the card's deck index
   * @return 0 when the play may go on, else the code it is refused with
   */
  public int gate(int side, int index) {
    MatchSide matchSide = sides.get(side);
    if (!kingAlive(side)) {
      return KING_DEAD;
    }
    int slot = matchSide.getHand().slotOf(index);
    if (slot < 0 || slot >= Hand.SLOTS) {
      return NOT_IN_HAND;
    }
    MatchCard card = matchSide.deck().get(index);
    if (card.mirror()) {
      throw new UnsupportedOperationException("the Mirror, which plays the last card again");
    }
    if (matchSide.wholeElixir() < card.cost()) {
      return NOT_ENOUGH_ELIXIR;
    }
    return 0;
  }

  /**
   * A play that passed the gates and was placed or cast: its cost taken and its card cycled.
   *
   * @param side the playing side
   * @param index the card's deck index
   */
  public void play(int side, int index) {
    sides.get(side).play(index);
  }
}
