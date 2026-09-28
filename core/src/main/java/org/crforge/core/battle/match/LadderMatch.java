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
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * A Ladder match between two players: the battle's clock, each king's elixir and hand, the gates a
 * card play passes before it is placed, and how the match ends.
 *
 * <p>The match is set up once the towers stand, side 0 first: each king starts with the timeline's
 * starting elixir, and its deck is shuffled into its battle order with one draw of the battle's
 * random source. Each step the update advances the timeline to the battle tick before the entities
 * are ticked; in their post-hook pass each king refills its hand and then regenerates its elixir,
 * at the rate and cooldown the timeline gives for that tick.
 *
 * <p>A card play first passes the match's gates, in order: the match must not be decided (code 4),
 * the king must be alive (code 8), the card must be in one of the hand's four slots (code 9), and
 * the whole elixir must cover its cost (code 0xd). A refused play changes nothing. One that places
 * or casts takes its cost and moves the card to the back of the queue before its units are created
 * or its spell cast.
 *
 * <p>The match is decided when a king has fallen, when overtime sees a crown, or when the time is
 * up. It is asked at the head of each step and after the entity tick, and the first time it holds
 * the match ends: the timeline freezes, the winner is the side with more crowns, and the end timer
 * starts at 1. The battle goes on - units fight, every play is refused - and each update adds 50 to
 * the timer, until the update that takes it to the end screen's delay ticks no entities and the
 * battle stops: from the next step, nothing runs, not even the tick counter. From the update after
 * a king's fall, a circle grows from it to the arena's length over half that delay and kills every
 * living entity of its side it reaches.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by match_elixir_150s: the setup in side order with the shuffle's draw,"
            + " the update's advance before the entity tick, the kings' refill and regeneration in"
            + " their post-hook, the gates 9 and 0xd and the spend and cycle before the placement's"
            + " units; by match_knights_king: the end at a king's fall, the winner, the end timer"
            + " and the entity ticks it allows, the fallen king's circle and the stop. Held by"
            + " LadderMatchTest alone: the gate 4 and the timeline's freeze. Not modelled, and"
            + " refused: the tiebreaker of equal crowns when the time is up; the Mirror. Not"
            + " carried: the flag set when a fallen king's two towers stand whole, whose readers"
            + " are not established.")
public final class LadderMatch implements BattleMode {

  /** The game mode row the match is played under. */
  public static final String GAME_MODE = "Ladder";

  /** The bound of the battle-source draw that seeds a deck's shuffle. */
  private static final int SHUFFLE_BOUND = 0x0fffffff;

  /** A refused play: the match is decided. */
  public static final int OVER = 4;

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

  /** How long the battle goes on after its end: the end screen's delay, in milliseconds. */
  private final int endDelayMs;

  /** The arena's length in game units, which a fallen king's circle grows to. */
  private final int arenaLength;

  /** Whether the match has ended. */
  private boolean ended;

  /** The end timer: 0 until the end, then 1, and 50 more each update. */
  private int endTimerMs;

  /** The side that won, or -1 for none. */
  private int winner;

  /** How long a fallen king's circle has grown, in milliseconds. */
  private int circleMs;

  /** Whether the last step ticked the entities. */
  private boolean lastTicked;

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
    this.endDelayMs = records.endScreenDelayMs();
    this.arenaLength = world.getTileMap().height() * TileMap.CELL_UNITS;
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
   * Whether the battle has stopped: the match is decided and its end timer has reached the end
   * screen's delay. A stopped battle runs no step at all.
   */
  @Override
  public boolean isOver() {
    return decided() && endTimerMs >= endDelayMs;
  }

  /**
   * The head of a step: a match decided since the last step is ended here, unless equal crowns in a
   * mode with no draws take it to the tiebreaker, which is not modelled.
   */
  @Override
  public void beforeCommands(Battle battle) {
    if (!decided()) {
      return;
    }
    refuseTiebreaker();
    end();
  }

  /**
   * The update: the crowns-equal byte, the timeline advanced to the battle tick, the circle of a
   * fallen king, and once the match has ended its end timer, 50 more each update; the update that
   * takes it to the end screen's delay ticks no entities.
   */
  @Override
  public boolean update(Battle battle) {
    timeline.setCrownsEqual(crowns(0) == crowns(1));
    timeline.advance(battle.getTick());
    for (int side = 0; side < 2; side++) {
      TowerEntity king = king(side);
      if (!HitPoints.alive(king.getHitPoints())) {
        circle(king);
      }
    }
    if (endTimerMs >= 1) {
      endTimerMs += 50;
      if (decided() && endTimerMs >= endDelayMs) {
        return false;
      }
    }
    return true;
  }

  /** The tail of a step: the crowns-equal byte again, and a match the tick decided is ended. */
  @Override
  public void afterTick(Battle battle, boolean ticked) {
    lastTicked = ticked;
    if (ticked) {
      timeline.setCrownsEqual(crowns(0) == crowns(1));
    }
    if (decided() && !tiebreakerArmed()) {
      end();
    }
  }

  /**
   * The end handler, once: the match ended, its timeline frozen, the end timer started at 1, and
   * the winner the side with more crowns, or none for equal crowns.
   */
  private void end() {
    if (ended) {
      return;
    }
    ended = true;
    timeline.freeze();
    endTimerMs = 1;
    int taken0 = crowns(0);
    int taken1 = crowns(1);
    winner = taken0 < taken1 ? 1 : taken0 > taken1 ? 0 : -1;
  }

  /**
   * Whether a decided match goes to the tiebreaker rather than its end: the crowns are equal, the
   * mode allows no draws, and the match has not ended.
   */
  private boolean tiebreakerArmed() {
    return crowns(0) == crowns(1) && !ended;
  }

  private void refuseTiebreaker() {
    if (tiebreakerArmed()) {
      throw new UnsupportedOperationException(
          "the time is up with equal crowns in a mode without draws, whose tiebreaker is not"
              + " modelled");
    }
  }

  /**
   * The circle from a fallen king: it grows to the arena's length over half the end screen's delay,
   * 50 ms an update from the update after the fall, and kills every living entity of the king's
   * side with hit points it reaches, in the holder's order, with no attacker.
   */
  private void circle(TowerEntity king) {
    circleMs += 50;
    int duration = endDelayMs / 2;
    int radius = arenaLength;
    if (duration >= 1) {
      radius = arenaLength * Math.min(circleMs, duration) / duration;
    }
    int x = king.getView().getX();
    int y = king.getView().getY();
    for (BattleEntity entity : List.copyOf(world.getHolder().entities())) {
      if (entity instanceof WorldEntity w
          && w.side() == king.side()
          && w.getHitPoints() != null
          && HitPoints.alive(w.getHitPoints())
          && inside(w.getView().getX(), w.getView().getY(), x, y, radius)) {
        world.circleKill(w, radius);
      }
    }
  }

  /** Whether a point lies within the circle: the square around it, then the squared distance. */
  private static boolean inside(int px, int py, int cx, int cy, int radius) {
    int dx = cx - px;
    int dy = cy - py;
    if (dx > radius || dx < -radius || dy > radius || dy < -radius) {
      return false;
    }
    checkArgument(radius <= 0x7ffe, () -> "a circle wider than the standard arena's length");
    return Integer.compareUnsigned(dx * dx + dy * dy, radius * radius) <= 0;
  }

  /** The end timer: 0 until the match ends, then 1 and 50 more each update. */
  public int getEndTimerMs() {
    return endTimerMs;
  }

  /** Whether the last step ticked the entities, rather than only cleaning the holder up. */
  public boolean isLastTicked() {
    return lastTicked;
  }

  /** Whether the match has ended. */
  public boolean isEnded() {
    return ended;
  }

  /** The winning side, or -1 for none; 0 until the match ends. */
  public int getWinner() {
    return winner;
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

  /** A side's king, which is never removed from the holder. */
  private TowerEntity king(int side) {
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.getData().king() && tower.side() == side) {
        return tower;
      }
    }
    throw new IllegalStateException("side " + side + " has no king tower");
  }

  /** Whether a side's king still has hit points. */
  private boolean kingAlive(int side) {
    return HitPoints.alive(king(side).getHitPoints());
  }

  /**
   * Whether the match is decided: its end timer runs, a king is dead, overtime has seen a crown, or
   * the time is up.
   */
  private boolean decided() {
    if (endTimerMs > 0) {
      return true;
    }
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
    if (decided()) {
      return OVER;
    }
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
