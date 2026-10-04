package org.crforge.desktop.battle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.desktop.GoldenScenario;

/**
 * One battle on the battle core as the visualizer plays it: the battle, how its cards are played
 * from the screen, and what the player is told when a play is refused.
 *
 * <p>There are two kinds of session:
 *
 * <ul>
 *   <li><b>a Ladder battle</b> ({@link #ladder}): the standard arena with its six towers at {@link
 *       #LEVEL}, played as a Ladder match between two decks, each card played from its king's hand
 *       through the battle's own play path, so the match's gates, its elixir, its hand cycle and
 *       its clock are the battle core's;
 *   <li><b>a golden scenario</b> ({@link #scenario}): the arena with passive towers and the
 *       scenario's unit placed on tick 0, outside any match, as the battle core's own golden
 *       trajectory test places it. It has no hands.
 * </ul>
 *
 * <p>A play is given as a player gives it, between two steps, and runs {@link
 * Standard1v1Battle#PLAY_DELAY_TICKS} ticks later. The session refuses a play on the spot, with a
 * message, when the match's gates would refuse it now (the match decided, the king dead, the elixir
 * short), when the card is still waiting for an earlier play of it to run, or when its placement
 * finds no tile; the elixir check sets aside the cost of the side's plays still waiting, as a
 * player's client does. A play the battle refuses when it runs, against the battle as it stands
 * then, is reported the same way from the battle's own record of the play.
 *
 * <p>The battle core refuses what it does not model by throwing from inside a step. The session
 * then stops stepping and keeps the message: a battle interrupted part way through a step is not
 * stepped again, and only a new session plays on.
 */
public final class BattleSession {

  /** The level every card is played at and every tower stands at: standard Ladder play. */
  public static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** How many messages the session keeps, newest last. */
  public static final int MESSAGES_KEPT = 6;

  @Getter private final Standard1v1Battle battle;

  /** The golden scenario this session replays, or null for a Ladder battle. */
  @Getter private final GoldenScenario.Case scenarioCase;

  /** The scenario's unit, or null for a Ladder battle. */
  @Getter private final CharacterEntity scenarioUnit;

  @Getter private final TrajectoryHub trajectories = new TrajectoryHub();

  @Getter private final AreaHitLog areaHits = new AreaHitLog();

  private final Deque<String> messages = new ArrayDeque<>();

  /** The card each play was given for, by the play's name. */
  private final Map<String, String> playedCards = new HashMap<>();

  /** The plays given and not yet run, by name. */
  private final Map<String, Pending> pending = new HashMap<>();

  /** How many of the battle's plays have been read for refusals. */
  private int playsRead;

  /** How many plays have been given, which numbers their names. */
  private int playsGiven;

  /** Why the battle stopped stepping, or null while it steps. */
  @Getter private String halted;

  /** A play given and not yet run: its side, its deck index and the cost it sets aside. */
  private record Pending(int side, int deckIndex, int cost) {}

  private BattleSession(
      Standard1v1Battle battle, GoldenScenario.Case scenarioCase, CharacterEntity scenarioUnit) {
    this.battle = battle;
    this.scenarioCase = scenarioCase;
    this.scenarioUnit = scenarioUnit;
    // Attached before the first step, so they hear every tick of the battle.
    trajectories.attach(battle.getWorld());
    battle.getWorld().addObserver(areaHits);
    if (scenarioUnit != null) {
      trajectories.follow(scenarioUnit);
    }
  }

  /**
   * A Ladder battle between two decks.
   *
   * @param tables the game tables
   * @param blue side 0's deck, by card row name
   * @param red side 1's deck, by card row name
   */
  public static BattleSession ladder(GameTables tables, List<String> blue, List<String> red) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL);
    // Both players' words are 0, so every new session deals and plays the same way.
    battle.startLadderMatch(blue, red, 0, 0);
    return new BattleSession(battle, null, null);
  }

  /** A Ladder battle between the visualizer's default decks, {@link BattleDecks}. */
  public static BattleSession ladder(GameTables tables) {
    return ladder(tables, BattleDecks.BLUE, BattleDecks.RED);
  }

  /**
   * A golden scenario: the towers passive and the case's unit placed on tick 0 at the case's point,
   * so battle tick {@code n} is the reference's tick {@code n}.
   *
   * @param tables the game tables
   * @param scenarioCase the case to replay
   */
  public static BattleSession scenario(GameTables tables, GoldenScenario.Case scenarioCase) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    UnitData unit = battle.getWorld().getRecords().unit(scenarioCase.card());
    CharacterEntity placed =
        battle.deploy(
            0, unit, LEVEL, scenarioCase.side(), scenarioCase.deployX(), scenarioCase.deployY());
    return new BattleSession(battle, scenarioCase, placed);
  }

  /**
   * A session over a battle built and set up elsewhere, before its first step: a Ladder match
   * started on it, or not, and any commands already queued on it, such as a replay's plays. The
   * session steps it, draws it and reports its refused plays as for its own; plays given from the
   * screen go through the same path when it is played as a match.
   *
   * @param battle the battle, not yet stepped
   */
  public static BattleSession of(Standard1v1Battle battle) {
    return of(battle, Map.of());
  }

  /**
   * A session over a battle built and set up elsewhere, as {@link #of(Standard1v1Battle)}, whose
   * queued plays are named after their cards: the messages about a play the battle refuses as it
   * runs name its card, not the play.
   *
   * @param battle the battle, not yet stepped
   * @param playCards the card of each queued play, by the play's name
   */
  public static BattleSession of(Standard1v1Battle battle, Map<String, String> playCards) {
    if (battle.getBattle().getTick() != 0) {
      throw new IllegalArgumentException(
          "a session starts before the battle's first step, not on tick "
              + battle.getBattle().getTick());
    }
    BattleSession session = new BattleSession(battle, null, null);
    session.playedCards.putAll(playCards);
    return session;
  }

  /** The Ladder match, or null for a golden scenario. */
  public LadderMatch match() {
    return battle.getMatch();
  }

  /** The battle's count of completed steps. */
  public int tick() {
    return battle.getBattle().getTick();
  }

  /** Whether the battle has stopped by its own rule: the match is over and its end has run. */
  public boolean isOver() {
    return battle.getBattle().getMode().isOver();
  }

  /**
   * Runs one step, unless the battle is over or halted, and reports the plays that ran in it and
   * were refused. A step the battle core refuses to finish halts the session.
   *
   * @return true when a step ran to its end
   */
  public boolean step() {
    if (halted != null || isOver()) {
      return false;
    }
    try {
      battle.getBattle().step();
    } catch (RuntimeException e) {
      halted = e.getClass().getSimpleName() + ": " + e.getMessage();
      say("battle stopped on tick " + tick() + ", " + halted + " (R resets)");
      return false;
    }
    readPlays();
    return true;
  }

  /**
   * The card in a hand slot.
   *
   * @param side 0 or 1
   * @param slot 0 to 3
   * @return the card, or null for an empty slot or a session without hands
   */
  public MatchCard handCard(int side, int slot) {
    LadderMatch match = match();
    if (match == null || slot < 0 || slot >= Hand.SLOTS) {
      return null;
    }
    int index = match.side(side).getHand().slots()[slot];
    return index == Hand.EMPTY ? null : match.side(side).deck().get(index);
  }

  /** The card that refills the hand next, or null for none or a session without hands. */
  public MatchCard nextCard(int side) {
    LadderMatch match = match();
    if (match == null) {
      return null;
    }
    List<Integer> queue = match.side(side).getHand().queue();
    return queue.isEmpty() ? null : match.side(side).deck().get(queue.get(0));
  }

  /** Whether a hand slot's card has been played and is waiting for its play to run. */
  public boolean isPending(int side, int slot) {
    LadderMatch match = match();
    if (match == null || slot < 0 || slot >= Hand.SLOTS) {
      return false;
    }
    int index = match.side(side).getHand().slots()[slot];
    return pending.values().stream().anyMatch(p -> p.side() == side && p.deckIndex() == index);
  }

  /**
   * Where a hand slot's card would be placed at a point now, or null when there is no card to place
   * there or it is not placed by a placement of its own (the Mirror, a variant card).
   */
  public CardPlacement.Result preview(int side, int slot, int x, int y) {
    DeployCard card = deployCard(handCard(side, slot));
    return card == null ? null : battle.previewPlacement(card, side, x, y);
  }

  /** The battle's row of a card, or null for the Mirror, a variant card or no card. */
  public DeployCard deployCard(MatchCard card) {
    if (card == null || card.mirror() || card.variant() != null) {
      return null;
    }
    return battle.getWorld().getRecords().card(card.name());
  }

  /**
   * Gives a play of a hand slot's card at a point, as the side's player does: refused on the spot
   * with a message, or queued to run {@link Standard1v1Battle#PLAY_DELAY_TICKS} ticks later.
   *
   * @param side 0 or 1
   * @param slot the hand slot, 0 to 3
   * @param x the requested point in game units
   * @param y the requested point in game units
   * @return true when the play was given
   */
  public boolean play(int side, int slot, int x, int y) {
    String who = sideName(side);
    LadderMatch match = match();
    if (match == null) {
      say(who + ": no hand in a golden scenario (R starts a Ladder battle)");
      return false;
    }
    if (halted != null) {
      say(who + ": the battle has stopped (R resets)");
      return false;
    }
    MatchCard card = handCard(side, slot);
    if (card == null) {
      say(who + ": slot " + (slot + 1) + " is empty");
      return false;
    }
    if (isPending(side, slot)) {
      say(who + ": " + card.name() + " is already played and waits to run");
      return false;
    }
    MatchSide matchSide = match.side(side);
    int deckIndex = matchSide.getHand().slots()[slot];
    int setAside =
        pending.values().stream().filter(p -> p.side() == side).mapToInt(Pending::cost).sum();
    // The Mirror and a variant card are gated on their item's cost, built as they run; the run's
    // gate is theirs alone.
    boolean itemCost = card.mirror() || card.variant() != null;
    if (!itemCost) {
      int code = match.gate(side, deckIndex, card.cost() + setAside);
      if (code != 0) {
        say(who + ": " + card.name() + " refused, " + refusal(code));
        return false;
      }
      CardPlacement.Result preview = preview(side, slot, x, y);
      if (!preview.placed()) {
        say(
            who
                + ": "
                + card.name()
                + " has no legal tile there (code "
                + hex(preview.code())
                + ")");
        return false;
      }
    }
    String name = (side == 0 ? "b" : "r") + (++playsGiven);
    int runTick = Math.max(tick(), 1) + Standard1v1Battle.PLAY_DELAY_TICKS;
    try {
      if (card.mirror()) {
        battle.playMirror(runTick, card.name(), LEVEL, side, x, y, name);
      } else if (card.variant() != null) {
        battle.playVariant(runTick, card.name(), LEVEL, side, x, y, name);
      } else {
        battle.submit(deployCard(card), LEVEL, side, x, y, name);
      }
    } catch (RuntimeException e) {
      say(who + ": " + card.name() + " refused, " + e.getMessage());
      return false;
    }
    playedCards.put(name, card.name());
    pending.put(name, new Pending(side, deckIndex, itemCost ? 0 : card.cost()));
    say(who + ": " + card.name() + " played, runs on tick " + runTick);
    return true;
  }

  /**
   * Adds a message of the screen's own to the session's, such as why a switch of data version was
   * refused.
   *
   * @param message the message, stamped with the current tick
   */
  public void note(String message) {
    say(message);
  }

  /**
   * Stops the session stepping, as a step the battle core refuses does, keeping the reason. The one
   * driving the session halts it when it finds the battle has left what it can follow, such as a
   * replay whose play ran with an item other than the replay's.
   *
   * @param reason why the session stops
   */
  public void halt(String reason) {
    if (halted == null) {
      halted = reason;
      say("battle stopped on tick " + tick() + ", " + reason + " (R resets)");
    }
  }

  /** The messages kept, oldest first. */
  public List<String> messages() {
    return new ArrayList<>(messages);
  }

  /** Reads the plays that ran since the last read and reports each one the battle refused. */
  private void readPlays() {
    List<Standard1v1Battle.Play> plays = battle.getPlays();
    for (; playsRead < plays.size(); playsRead++) {
      Standard1v1Battle.Play play = plays.get(playsRead);
      pending.remove(play.name());
      String card = playedCards.getOrDefault(play.name(), play.name());
      String who = sideName(play.side());
      if (play.matchCode() != 0) {
        say(who + ": " + card + " refused as it ran, " + refusal(play.matchCode()));
      } else if (play.result() != null && !play.result().placed()) {
        say(
            who
                + ": "
                + card
                + " found no legal tile as it ran (code "
                + hex(play.result().code())
                + ")");
      }
    }
  }

  /** What a match gate's refusal code means. */
  static String refusal(int code) {
    return switch (code) {
      case LadderMatch.OVER -> "the match is decided";
      case LadderMatch.KING_DEAD -> "the king is dead";
      case LadderMatch.NOT_IN_HAND -> "the card is not in the hand";
      case LadderMatch.NOT_ENOUGH_ELIXIR -> "not enough elixir";
      default -> "code " + hex(code);
    };
  }

  /** The name the messages give a side. */
  public static String sideName(int side) {
    return side == 0 ? "blue" : "red";
  }

  private static String hex(int code) {
    return "0x" + Integer.toHexString(code);
  }

  private void say(String message) {
    messages.addLast("[" + tick() + "] " + message);
    while (messages.size() > MESSAGES_KEPT) {
      messages.removeFirst();
    }
  }
}
