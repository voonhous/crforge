/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import lombok.Getter;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.PlayerCommands.Giving;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.desktop.model.ModelOpponent;
import org.crforge.env.Schema;

/**
 * One battle on the battle core as the visualizer plays it: the battle, how its cards are played
 * from the screen, and what the player is told when a play is refused.
 *
 * <p>The screen starts <b>a Ladder battle</b> ({@link #ladder}): the standard arena with its six
 * towers at {@link #LEVEL}, played as a Ladder match between two decks, each card played from its
 * king's hand through the battle's own play path, so the match's gates, its elixir, its hand cycle
 * and its clock are the battle core's. A battle built elsewhere, such as a replay's, is wrapped by
 * {@link #of}.
 *
 * <p>A play is given as a player gives it, between two steps, through the battle core's {@link
 * PlayerCommands}, and runs {@link Standard1v1Battle#PLAY_DELAY_TICKS} ticks later. A play is sent
 * on the tile the placement search finds from the clicked point now, as the player's screen sends
 * it, and the preview shows the play placed from that tile. The session refuses a play on the spot,
 * with a message, when the player's client would drop it: the match's gates now (the match decided,
 * the king dead), or the elixir short once the costs the side's waiting plays promised are set
 * aside; or when the screen does not offer the card, a play of it still waiting to run, or its
 * search finds no tile, as for a Mirror whose king has no last card. A play the battle refuses when
 * it runs, against the battle as it stands then, a point where its placement finds no tile among
 * them, is reported the same way from the battle's own record of the play.
 *
 * <p>A session against a model ({@link #withModel}) plays the environment's battle: one side is the
 * model's, which acts as each decision opens through the environment's own path ({@link
 * ModelOpponent}), and the screen plays the other through the same player's commands.
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

  @Getter private final AreaHitLog areaHits = new AreaHitLog();

  /** The messages kept, oldest first, each naming its side when it has one. */
  private final Deque<Message> messages = new ArrayDeque<>();

  /** The card each play was given for, by the play's name. */
  private final Map<String, String> playedCards = new HashMap<>();

  /**
   * Each side's card levels by deck index, counted from 1 across all rarities, or null when every
   * card is at {@link #LEVEL}, as in a Ladder battle the screen starts.
   */
  private final List<int[]> deckLevels;

  /**
   * The players' commands given from the screen, with the client's checks and promises; null for a
   * battle with no match started on it.
   */
  private final PlayerCommands commands;

  /** The deploy overlay's tiles of the battle, kept until the field changes. */
  private final LandingTiles landing;

  /** How many of the battle's plays have been read for refusals. */
  private int playsRead;

  /** How many plays have been given, which numbers their names. */
  private int playsGiven;

  /** Why the battle stopped stepping, or null while it steps. */
  @Getter private String halted;

  /** The model playing one side, or null when the screen plays both. */
  @Getter private final ModelOpponent model;

  private BattleSession(Standard1v1Battle battle, List<int[]> deckLevels) {
    this(battle, deckLevels, null);
  }

  private BattleSession(Standard1v1Battle battle, List<int[]> deckLevels, ModelOpponent model) {
    this.battle = battle;
    this.deckLevels = deckLevels == null ? null : List.copyOf(deckLevels);
    this.model = model;
    // Against a model, the environment's commands: the screen's plays and the model's share their
    // promises (plays waiting to run, the elixir they hold back).
    this.commands =
        model != null
            ? model.live().commands()
            : battle.getMatch() == null ? null : new PlayerCommands(battle);
    this.landing = new LandingTiles(battle);
    // Attached before the first step, so it hears every tick of the battle.
    battle.getWorld().addObserver(areaHits);
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
    return new BattleSession(battle, null);
  }

  /**
   * A Ladder battle against a model, on the environment's battle the model's opponent started,
   * before its first step: every card of both sides at the level the environment plays.
   *
   * @param opponent the model and its battle
   */
  public static BattleSession withModel(ModelOpponent opponent) {
    Standard1v1Battle battle = opponent.live().battle();
    if (battle.getBattle().getTick() != 0) {
      throw new IllegalArgumentException("a session starts before the battle's first step");
    }
    int[] levels = new int[Schema.DECK_SIZE];
    Arrays.fill(levels, opponent.live().config().level());
    return new BattleSession(battle, List.of(levels, levels.clone()), opponent);
  }

  /** A Ladder battle between the visualizer's default decks, {@link BattleDecks}. */
  public static BattleSession ladder(GameTables tables) {
    return ladder(tables, BattleDecks.BLUE, BattleDecks.RED);
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
    return of(battle, playCards, null);
  }

  /**
   * A session over a battle built and set up elsewhere, as {@link #of(Standard1v1Battle, Map)},
   * whose decks hold cards at their own levels, such as a replay's: the hands show each card's
   * level, and a play given from the screen runs at it.
   *
   * @param battle the battle, not yet stepped
   * @param playCards the card of each queued play, by the play's name
   * @param deckLevels each side's card levels by deck index, counted from 1 across all rarities, in
   *     the order of the decks the match was started with; null for every card at {@link #LEVEL}
   */
  public static BattleSession of(
      Standard1v1Battle battle, Map<String, String> playCards, List<int[]> deckLevels) {
    if (battle.getBattle().getTick() != 0) {
      throw new IllegalArgumentException(
          "a session starts before the battle's first step, not on tick "
              + battle.getBattle().getTick());
    }
    BattleSession session = new BattleSession(battle, deckLevels);
    session.playedCards.putAll(playCards);
    return session;
  }

  /** The Ladder match, or null for a battle with no match started on it. */
  public LadderMatch match() {
    return battle.getMatch();
  }

  /** The battle's count of completed steps. */
  public int tick() {
    return battle.getBattle().getTick();
  }

  /** Whether the battle has stopped by its own rule: the match is over and its end has run. */
  public boolean isOver() {
    return battle.getBattle().getMode().isOver() || (model != null && model.live().done());
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
      if (model == null) {
        battle.getBattle().step();
      } else {
        if (model.atDecision()) {
          tell(model.act());
        }
        model.live().step();
      }
    } catch (RuntimeException e) {
      halted = e.getClass().getSimpleName() + ": " + e.getMessage();
      say("battle stopped on tick " + tick() + ", " + halted + " (R resets)");
      return false;
    }
    readPlays();
    return true;
  }

  /** Tells the player what the model did as a decision opened: a play given, or refused. */
  private void tell(ModelOpponent.Turn turn) {
    int action = turn.decision().action();
    if (action == Schema.WAIT) {
      return;
    }
    String what =
        Schema.isAbility(action)
            ? "champion ability " + (Schema.abilitySlot(action) + 1)
            : turn.card() == null ? "an empty slot" : turn.card();
    if (turn.refusal() != Schema.REFUSAL_NONE) {
      say(model.side(), ": the model's " + what + " was refused (code " + turn.refusal() + ")");
      return;
    }
    if (turn.command() != null) {
      playedCards.put(turn.command().name(), what);
      say(model.side(), ": the model plays " + what + ", runs on tick " + turn.command().tick());
    }
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

  /**
   * The level a side plays a deck card at.
   *
   * @param side 0 or 1
   * @param deckIndex the card's index in the side's deck
   * @return the level, counted from 1 across all rarities
   */
  public int cardLevel(int side, int deckIndex) {
    return deckLevels == null ? LEVEL : deckLevels.get(side)[deckIndex];
  }

  /**
   * The level of the card in a hand slot.
   *
   * @param side 0 or 1
   * @param slot 0 to 3
   * @return the level, or 0 for an empty slot or a session without hands
   */
  public int handCardLevel(int side, int slot) {
    if (handCard(side, slot) == null) {
      return 0;
    }
    return cardLevel(side, match().side(side).getHand().slots()[slot]);
  }

  /** The level of the card that refills the hand next, or 0 for none. */
  public int nextCardLevel(int side) {
    if (nextCard(side) == null) {
      return 0;
    }
    return cardLevel(side, match().side(side).getHand().queue().get(0));
  }

  /** Whether a hand slot's card has been played and is waiting for its play to run. */
  public boolean isPending(int side, int slot) {
    LadderMatch match = match();
    if (match == null || slot < 0 || slot >= Hand.SLOTS) {
      return false;
    }
    int index = match.side(side).getHand().slots()[slot];
    return index != Hand.EMPTY && commands.isPending(side, index);
  }

  /**
   * Where a hand slot's card dropped at a point would be placed now: the play placed from the tile
   * the placement search finds there, the tile it is sent on, against the battle as it stands now;
   * refused where the search finds no tile; null when there is no card to place there or it is not
   * placed by a placement of its own (the Mirror, a variant card).
   */
  public CardPlacement.Result preview(int side, int slot, int x, int y) {
    DeployCard card = deployCard(handCard(side, slot));
    if (card == null) {
      return null;
    }
    int[] tile = battle.searchPlacement(card, side, x, y);
    if (tile == null) {
      return new CardPlacement.Result(CardPlacement.NO_POSITION, 0, 0, null, 0, List.of());
    }
    return battle.previewPlacement(card, side, tile[0], tile[1]);
  }

  /** The battle's row of a card, or null for the Mirror, a variant card or no card. */
  public DeployCard deployCard(MatchCard card) {
    if (card == null || card.mirror() || card.variant() != null) {
      return null;
    }
    return battle.getWorld().getRecords().card(card.name());
  }

  /**
   * The tiles a hand slot's card lands on without being moved now, as the deploy overlay shades
   * them ({@link LandingTiles}): from the cards the screen's search is handed for a play of the
   * slot ({@link PlayerCommands#searchCards}), on the field the side sees.
   *
   * @param side 0 or 1
   * @param slot the hand slot, 0 to 3
   * @return the tiles, or null when the client's try refuses the play now, the battle has stopped,
   *     there is no match, or the battle core refuses the try as not modelled
   */
  public LandingTiles.Tiles landingTiles(int side, int slot) {
    List<DeployCard> cards = searchCards(side, slot);
    if (cards.isEmpty()) {
      return null;
    }
    try {
      return landing.tiles(side, cards);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Where a drop of a hand slot's card at a tile's centre goes now, as the screen sends the play:
   * the point the battle's placement search finds, and whether the overlay shows the tile open.
   *
   * @param side 0 or 1
   * @param slot the hand slot, 0 to 3
   * @param tileX the tile's column
   * @param tileY the tile's row
   * @return the landing, or null when {@link #landingTiles} has no tiles
   */
  public LandingTiles.Landing landingAt(int side, int slot, int tileX, int tileY) {
    List<DeployCard> cards = searchCards(side, slot);
    if (cards.isEmpty()) {
      return null;
    }
    try {
      return landing.landing(side, cards, tileX, tileY);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The cards the screen's search is handed for a play of a hand slot now, or none when the play is
   * not offered: the battle stopped, no match, an empty slot, or the client's try refuses it.
   */
  private List<DeployCard> searchCards(int side, int slot) {
    if (commands == null || halted != null || isOver() || handCard(side, slot) == null) {
      return List.of();
    }
    try {
      return commands.searchCards(side, slot, handCardLevel(side, slot));
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /**
   * Why a hand card cannot be selected or given now, or null: the client's checks of {@link
   * PlayerCommands#tryCard}, which need no point. A Mirror's and a variant card's item is built as
   * the client builds it at the giving, and its cost checked.
   */
  public String cardUnavailableReason(int side, int slot) {
    LadderMatch match = match();
    if (match == null) return "no hand in a battle without a match (R starts a Ladder battle)";
    if (halted != null || isOver()) return "the battle has stopped (R resets)";
    MatchCard card = handCard(side, slot);
    if (card == null) return "slot " + (slot + 1) + " is empty";
    Giving tried;
    try {
      tried =
          commands.tryCard(side, slot, cardLevel(side, match.side(side).getHand().slots()[slot]));
    } catch (RuntimeException e) {
      return card.name() + " refused, " + e.getMessage();
    }
    return tried.given() ? null : reason(card, tried);
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
    if (model != null && side == model.side()) {
      say(side, ": played by the model");
      return false;
    }
    String unavailable = cardUnavailableReason(side, slot);
    if (unavailable != null) {
      say(side, ": " + unavailable);
      return false;
    }
    LadderMatch match = match();
    MatchCard card = handCard(side, slot);
    int deckIndex = match.side(side).getHand().slots()[slot];
    String name = (side == 0 ? "b" : "r") + (playsGiven + 1);
    Giving giving;
    try {
      giving =
          model == null
              ? commands.playCard(side, slot, cardLevel(side, deckIndex), x, y, name)
              // through the environment, which keeps the play as it keeps its seats' plays
              : model.live().play(side, slot, x, y, name);
    } catch (RuntimeException e) {
      say(side, ": " + card.name() + " refused, " + e.getMessage());
      return false;
    }
    if (!giving.given()) {
      say(side, ": " + reason(card, giving));
      return false;
    }
    playsGiven++;
    playedCards.put(name, card.name());
    say(side, ": " + card.name() + " played, runs on tick " + giving.runTick());
    return true;
  }

  /** What the player is told of a giving the client or the screen refused. */
  private static String reason(MatchCard card, Giving giving) {
    return switch (giving.refusal()) {
      case CARD_PENDING -> card.name() + " is already played and waits to run";
      case NO_TILE -> card.name() + " has no legal tile";
      case MATCH_OVER, KING_DEAD, NOT_IN_HAND, START_COOLDOWN, NOT_ENOUGH_ELIXIR ->
          card.name() + " refused, " + refusal(giving.code());
      default ->
          card.name()
              + " refused, "
              + giving.refusal().name().toLowerCase(Locale.ROOT)
              + " (code "
              + hex(giving.code())
              + ")";
    };
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
   * Adds a message about one side, such as a replay's play as it ran. The side is named when the
   * messages are read, by the names the reader gives the sides, so a message names a side by its
   * colour on screen whichever way up the arena is drawn.
   *
   * @param side the side the message is about, 0 or 1
   * @param message the message, read after the side's name, such as {@code " plays Knight"}
   */
  public void note(int side, String message) {
    say(side, message);
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

  /** The messages kept, oldest first, naming the sides as {@link #sideName} does. */
  public List<String> messages() {
    return messages(BattleSession::sideName);
  }

  /**
   * The messages kept, oldest first, each stamped with its tick.
   *
   * @param sideNames the name a message about a side gives it
   */
  public List<String> messages(IntFunction<String> sideNames) {
    List<String> lines = new ArrayList<>(messages.size());
    for (Message message : messages) {
      lines.add(
          "["
              + message.tick()
              + "] "
              + (message.side() < 0 ? "" : sideNames.apply(message.side()))
              + message.text());
    }
    return lines;
  }

  /** Reads the plays that ran since the last read and reports each one the battle refused. */
  private void readPlays() {
    List<Standard1v1Battle.Play> plays = battle.getPlays();
    for (; playsRead < plays.size(); playsRead++) {
      Standard1v1Battle.Play play = plays.get(playsRead);
      String card = playedCards.getOrDefault(play.name(), play.name());
      if (play.matchCode() != 0) {
        say(play.side(), ": " + card + " refused as it ran, " + refusal(play.matchCode()));
      } else if (play.result() != null && !play.result().placed()) {
        say(
            play.side(),
            ": "
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
      case LadderMatch.START_COOLDOWN -> "the battle's start cooldown runs";
      case LadderMatch.NOT_ENOUGH_ELIXIR -> "not enough elixir";
      default -> "code " + hex(code);
    };
  }

  /** The name the messages give a side with side 0 at the bottom, as the Ladder screen draws it. */
  public static String sideName(int side) {
    return side == 0 ? "blue" : "red";
  }

  private static String hex(int code) {
    return "0x" + Integer.toHexString(code);
  }

  private void say(String message) {
    say(-1, message);
  }

  private void say(int side, String message) {
    messages.addLast(new Message(tick(), side, message));
    while (messages.size() > MESSAGES_KEPT) {
      messages.removeFirst();
    }
  }

  /**
   * One message as kept: its tick, the side it names first (-1 for none) and the rest of its text.
   */
  private record Message(int tick, int side, String text) {}
}
