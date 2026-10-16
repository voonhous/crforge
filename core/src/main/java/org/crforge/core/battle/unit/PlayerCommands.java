/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkArgument;
import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.MapCheck;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.match.VariantItem;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The commands of a Ladder battle's players, given as a player's client gives them: what a player
 * or an agent of the battle core calls to play a card or tap a champion's ability between two
 * steps, so its command runs as the opponent's does.
 *
 * <p>A command is given at the battle's tick counter, the number of the next step: it is stamped
 * with that counter, or 1 while the counter is still 0, and runs in the command pass of the step
 * {@link Standard1v1Battle#PLAY_DELAY_TICKS} after the stamp, against the battle as it stands then,
 * whatever kind it is: a deck card's play, a Mirror's, a variant card's, or an ability command.
 *
 * <p>A card play is not sent on the point it is dropped on. The player's screen runs the play's own
 * placement search on that point against the battle as it stands at the drop, the search the run
 * makes, and the command carries the tile it finds: a tile centre, less 1 on a coordinate the
 * symmetrical snap shifts, or a building's tile corner, for a spell as for a troop. A troop dropped
 * in the enemy half, on the river, on a tower, off the map or on a cell the map check refuses is
 * moved to the nearest legal tile, not refused; a drop is refused only when the search finds no
 * tile ({@link Refusal#NO_TILE}). On the standard arena only a Mirror finds none, while its king
 * has no last card: the search places a Mirror as the king's last card. The play's run searches
 * again from the tile sent, against the battle as it stands then, so a tower or building that falls
 * or lands in between moves the play again.
 *
 * <p>Before it sends a command the client tries it against the battle as it stands, and drops it,
 * with nothing changed, when that try refuses it. For a card play the try is, in order: the match's
 * gates (the match decided, the king dead, the card in one of the hand's slots, the tick counter
 * below the game mode's start cooldown, so no play is given before it), then the king's whole
 * elixir, less the costs the side's commands still waiting have promised and never below 0,
 * covering the play's item's cost, then the map check on the point sent, which a tile the search
 * found always passes. The screen's search comes before the try: a drop both refuse is reported
 * here as the try refuses it, and is sent by neither. For an ability command the try is the run's
 * gates in order with the elixir gate reading the elixir less the promised costs; {@link
 * #tryAbility} makes that try without giving the command, as {@link #tryCard} does a play's. The
 * client builds the item a play carries as it gives it: a deck card's from the side's count now, a
 * Mirror's from the last play of its side it still lists as promised or else the king's last card,
 * a variant card's option picked from the king's elixir less the promised costs.
 *
 * <p>A command the try passes promises its item's cost from its giving: a play until its run places
 * it, or, refused at its run, until {@value Standard1v1Battle#PROMISE_TICKS} ticks after the tick
 * counter it was given at; an ability command until its run, whatever it comes to. The promised
 * costs of a side are set aside by every later giving of that side, so a side may have several
 * commands waiting at once, each covered.
 *
 * <p>Besides the client's try, the player's screen does not offer a card again while a play of it
 * given from the same hand slot is waiting to run: no command of a recorded Ladder battle plays a
 * card while a play of the same card of its side waits, though tens of thousands are given while
 * another play of their side waits. That is refused here as {@link Refusal#CARD_PENDING}.
 *
 * <p>Only the commands given through this object are counted as promised: a command queued on the
 * battle with its run tick, as a replay's, promises nothing here.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the stamp and the 20 ticks for every Ladder command kind, held by every"
            + " recorded command; a play sent on the tile the screen's placement search finds at"
            + " the drop, refused only when it finds none, held by every live play sent on a tile"
            + " centre or a building's corner; the client's try at the giving: the match's gates"
            + " (the start cooldown among them, which no recording holds at the giving), the"
            + " elixir less the promised costs for a play, the"
            + " run's gates with the promised costs set aside for an ability command; the promise"
            + " of each command, its expiry 60 ticks after the counter it was given at and its"
            + " release at the run. Held by the recorded Ladder battles: no card is played while a"
            + " play of it of its side waits. Not traced to the tick: the step that drops a"
            + " refused play's expired promise, so a giving whose outcome a promise within two"
            + " ticks of its expiry decides is refused, and so is a Mirror whose item would repeat"
            + " a waiting Mirror's or variant card's play. No recording holds a giving that the"
            + " client refused: the recordings hold the commands it sent. Inferred, not traced:"
            + " the screen's search is handed the row of the dragged hand card, an evolved or"
            + " hero play's row and a variant card's option as the run places them, and a Mirror's"
            + " own row, which the search places as the king's last card; a Mirror whose item"
            + " repeats a listed play of its side is refused when that play's card and the king's"
            + " last card would be sent on different tiles. Not modelled: the screen's cancel of a card"
            + " dragged back over the hand, a gesture and no point.")
public final class PlayerCommands {

  /** Why a giving was refused, or {@link #NONE} for a command given. */
  public enum Refusal {
    /** The command was given. */
    NONE,
    /** The match is decided: a play's gate {@link LadderMatch#OVER}. */
    MATCH_OVER,
    /** The side's king is dead. */
    KING_DEAD,
    /** The card is in none of the hand's slots, or the slot is empty. */
    NOT_IN_HAND,
    /**
     * The tick counter is below the game mode's start cooldown: {@link LadderMatch#START_COOLDOWN}.
     */
    START_COOLDOWN,
    /** A play of the hand slot's card is waiting to run: the screen does not offer it again. */
    CARD_PENDING,
    /** The elixir less the promised costs does not cover the cost. */
    NOT_ENOUGH_ELIXIR,
    /** The screen's placement search finds no tile for the play: nothing is sent. */
    NO_TILE,
    /** No unit of the side is found for an ability command, or no champion slot follows it. */
    NO_CHAMPION,
    /** The champion slot's cooldown runs. */
    COOLDOWN,
    /** The champion slot has no charge left. */
    NO_CHARGES,
    /** The unit's ability is none, or no champion's. */
    NOT_CHAMPION,
    /** The unit holds its ability pending. */
    ABILITY_PENDING,
    /** The unit is a clone. */
    CLONE,
    /** The unit cannot act and the game refuses a frozen champion's ability. */
    FROZEN,
    /** The unit carries the tag that disables its ability. */
    ABILITY_DISABLED
  }

  /**
   * What a giving came to.
   *
   * @param refusal why it was refused, or {@link Refusal#NONE} when it was given
   * @param code the command's own code for the refusal: a match gate's or an ability gate's; 0 when
   *     given, {@link #SCREEN} for {@link Refusal#CARD_PENDING} and {@link Refusal#NO_TILE}, which
   *     the screen refuses
   * @param givenTick the tick it is stamped with: the tick counter, or 1 while it is 0
   * @param runTick the tick it runs on, or would have run on: the stamp plus {@link
   *     Standard1v1Battle#PLAY_DELAY_TICKS}
   * @param cost the whole elixir it promised, or would have: its item's cost, or the ability's; 0
   *     when it was refused before its cost was known
   * @param x the point a given play's command carries, the tile the screen's placement search
   *     found, in game units; {@link #NO_POINT} for a refused giving and an ability command
   * @param y the point a given play's command carries, as {@code x}
   */
  public record Giving(
      Refusal refusal, int code, int givenTick, int runTick, int cost, int x, int y) {

    /** A giving that carries no point: refused, or an ability command. */
    Giving(Refusal refusal, int code, int givenTick, int runTick, int cost) {
      this(refusal, code, givenTick, runTick, cost, NO_POINT, NO_POINT);
    }

    /** Whether the command was given, and runs on {@link #runTick}. */
    public boolean given() {
      return refusal == Refusal.NONE;
    }
  }

  /** The no-code mark of a refusal the screen makes, not the client's try. */
  public static final int SCREEN = -1;

  /** The point of a giving that carries none. */
  public static final int NO_POINT = -1;

  /** What a given command plays. */
  private enum Kind {
    CARD,
    MIRROR,
    VARIANT,
    ABILITY
  }

  /**
   * A command given, with the promise its client keeps for it.
   *
   * @param side the giving side
   * @param kind what it plays
   * @param deckIndex the deck index of the card a play was given for; -1 for an ability command
   * @param name the command's name, which its run records under
   * @param runTick the tick it runs on
   * @param expiresTick the tick counter its promise lasts until, refused at its run
   * @param cost the whole elixir it promised
   */
  private record Given(
      int side, Kind kind, int deckIndex, String name, int runTick, int expiresTick, int cost) {}

  /** Where a promise stands at a giving. */
  private enum Standing {
    /** Its command has not run: promised. */
    WAITING,
    /** Its play was refused at its run and its promise is within its 60 ticks: promised. */
    KEPT,
    /** Its play was refused at its run, its promise dropped about now, at a tick not traced. */
    DROPPING,
    /** Released: by the run, or dropped. */
    RELEASED
  }

  /**
   * How many ticks either side of a refused play's promise expiry the step that drops it may fall:
   * which count of the tick it reads is not traced, as for {@link Standard1v1Battle}'s Mirror.
   */
  private static final int DROP_WINDOW = 2;

  /**
   * A play's try worked out before its point is searched: its refusal, or its card's deck index and
   * kind, the cost it promises, the card the screen's search places, a variant card's option, and,
   * for a Mirror whose item repeats a listed play of its side in place of the king's last card, the
   * card the item repeats, else null.
   */
  private record Tried(
      Giving refused,
      int deckIndex,
      Kind kind,
      int cost,
      DeployCard searchCard,
      int option,
      DeployCard itemCard) {}

  private final Standard1v1Battle battle;

  /** Every command given, in the order given. */
  private final List<Given> given = new ArrayList<>();

  /**
   * The commands of a battle played as a Ladder match.
   *
   * @param battle the battle, its match started
   */
  public PlayerCommands(Standard1v1Battle battle) {
    checkArgument(
        battle.getMatch() != null, "a player's commands are given in a match, which has hands");
    this.battle = battle;
  }

  /**
   * Gives a play of the card in one of a side's hand slots dropped at a point, as its player does
   * now: a deck card's, a Mirror's or a variant card's. The play is sent on the tile the placement
   * search finds from the point against the battle as it stands now, and refused when it finds
   * none. Given, it runs {@link Standard1v1Battle#PLAY_DELAY_TICKS} ticks after its stamp through
   * the battle's play path, which searches again from the tile sent, and promises its item's cost;
   * refused, nothing changes.
   *
   * @param side the playing side
   * @param slot the hand slot, 0 to 3
   * @param level the level it is played at, counted from 1
   * @param x the dropped point in game units
   * @param y the dropped point in game units
   * @param name the play's name, new to this battle: unit {@code k} is named {@code name_k}
   * @return what the giving came to, with the tile sent when given
   */
  public Giving playCard(int side, int slot, int level, int x, int y, String name) {
    checkNameNew(name);
    Tried tried = tryCard(side, slot, level, name);
    if (tried.refused() != null) {
      return tried.refused();
    }
    int tick = battle.getBattle().getTick();
    int givenTick = stamp(tick);
    int runTick = givenTick + Standard1v1Battle.PLAY_DELAY_TICKS;
    int[] tile = battle.searchPlacement(tried.searchCard(), side, x, y);
    if (tried.itemCard() != null
        && !Arrays.equals(tile, battle.searchPlacement(tried.itemCard(), side, x, y))) {
      throw new UnsupportedOperationException(
          name
              + ": a Mirror whose item repeats a listed play, dropped where that play's card and"
              + " the king's last card are placed on different tiles, is not modelled: which of"
              + " the two the screen's search is handed is not traced");
    }
    if (tile == null) {
      return new Giving(Refusal.NO_TILE, SCREEN, givenTick, runTick, tried.cost());
    }
    // The try's map check on the tile sent passes: the search took each of the tile's map cells.
    int px = tile[0];
    int py = tile[1];
    checkState(
        MapCheck.check(battle.getWorld().getTileMap(), tried.searchCard(), px, py) == MapCheck.OK,
        () -> name + ": the map check refuses the tile the search found");
    MatchCard card = battle.getMatch().side(side).deck().get(tried.deckIndex());
    switch (tried.kind()) {
      case MIRROR -> battle.playMirror(givenTick, runTick, card.name(), level, side, px, py, name);
      case VARIANT ->
          battle.playVariant(
              givenTick, runTick, card.name(), tried.option(), level, side, px, py, name);
      default ->
          battle.play(givenTick, runTick, records().card(card.name()), level, side, px, py, name);
    }
    given.add(
        new Given(
            side,
            tried.kind(),
            tried.deckIndex(),
            name,
            runTick,
            tick + Standard1v1Battle.PROMISE_TICKS,
            tried.cost()));
    return new Giving(Refusal.NONE, 0, givenTick, runTick, tried.cost(), px, py);
  }

  /**
   * Tries a play of the card in a hand slot as {@link #playCard} does, without a point and without
   * giving it: whether the screen offers it and the client's try passes. It does not run the
   * placement search, which on the standard arena finds a tile from every point but for a Mirror
   * whose king has no last card, which it refuses as {@link Refusal#NO_TILE} wherever it is
   * dropped.
   *
   * @param side the playing side
   * @param slot the hand slot, 0 to 3
   * @param level the level it would be played at, counted from 1
   * @return what a giving now would come to, but for its point
   */
  public Giving tryCard(int side, int slot, int level) {
    Tried tried = tryCard(side, slot, level, "a play of slot " + slot);
    if (tried.refused() != null) {
      return tried.refused();
    }
    int givenTick = stamp(battle.getBattle().getTick());
    return new Giving(
        Refusal.NONE, 0, givenTick, givenTick + Standard1v1Battle.PLAY_DELAY_TICKS, tried.cost());
  }

  /**
   * Gives an ability command naming a unit a play of the side made, as its player taps the
   * champion's button now. Given, it runs {@link Standard1v1Battle#PLAY_DELAY_TICKS} ticks after
   * its stamp, and promises the ability's cost until then; refused, nothing changes.
   *
   * @param side the commanding side
   * @param unit the name of the unit it names, made by a play of the side that has run
   * @param name the command's name, new to this battle
   * @return what the giving came to
   */
  public Giving useAbility(int side, String unit, String name) {
    checkNameNew(name);
    Giving tried = tryAbility(side, unit, name);
    if (!tried.given()) {
      return tried;
    }
    battle.useAbility(tried.runTick(), side, unit, name);
    given.add(
        new Given(
            side,
            Kind.ABILITY,
            -1,
            name,
            tried.runTick(),
            battle.getBattle().getTick() + Standard1v1Battle.PROMISE_TICKS,
            tried.cost()));
    return tried;
  }

  /**
   * Tries an ability command naming a unit as {@link #useAbility} does, without giving it: whether
   * the client would send it now. The same gates in the same order, so it comes to what a giving
   * now would, and nothing changes: nothing is queued or promised.
   *
   * @param side the commanding side
   * @param unit the name of the unit it would name, made by a play of the side that has run
   * @return what a giving now would come to
   */
  public Giving tryAbility(int side, String unit) {
    return tryAbility(side, unit, "an ability command naming " + unit);
  }

  /** The client's try of an ability command: the run's gates, the promised costs set aside. */
  private Giving tryAbility(int side, String unit, String name) {
    int givenTick = stamp(battle.getBattle().getTick());
    int runTick = givenTick + Standard1v1Battle.PLAY_DELAY_TICKS;
    CharacterEntity named = playedUnit(side, unit);
    if (named == null) {
      return new Giving(Refusal.NO_CHAMPION, AbilityCommand.NO_CHAMPION, givenTick, runTick, 0);
    }
    int[] promised = promisedSplit(side);
    int code = AbilityCommand.tryGates(battle.getWorld(), side, named, promised[0]);
    if (promised[1] > 0
        && AbilityCommand.tryGates(battle.getWorld(), side, named, promised[0] + promised[1])
            != code) {
      throw new UnsupportedOperationException(
          name
              + ": an ability command whose giving the expiry of a refused play's promise"
              + " decides, at a tick not traced, is not modelled");
    }
    int cost = named.getData().ability() == null ? 0 : named.getData().ability().manaCost();
    if (code != AbilityCommand.OK) {
      return new Giving(abilityRefusal(code), code, givenTick, runTick, cost);
    }
    return new Giving(Refusal.NONE, 0, givenTick, runTick, cost);
  }

  /**
   * The whole elixir a side's client holds back now for the commands given through this object:
   * each waiting command's cost, and each play refused at its run whose promise is kept. A refused
   * play's promise within two ticks of its expiry is counted too, as the client may still keep it.
   *
   * @param side the side
   */
  public int promised(int side) {
    int[] promised = promisedSplit(side);
    return promised[0] + promised[1];
  }

  /**
   * Whether a play of a side's deck card given through this object is waiting to run.
   *
   * @param side the side
   * @param deckIndex the card's deck index
   */
  public boolean isPending(int side, int deckIndex) {
    for (Given command : given) {
      if (command.side() == side
          && command.kind() != Kind.ABILITY
          && command.deckIndex() == deckIndex
          && standing(command) == Standing.WAITING) {
        return true;
      }
    }
    return false;
  }

  /** The client's try of a play, but for its point. */
  private Tried tryCard(int side, int slot, int level, String name) {
    checkArgument(slot >= 0 && slot < Hand.SLOTS, () -> "no hand slot " + slot);
    LadderMatch match = battle.getMatch();
    int tick = battle.getBattle().getTick();
    int givenTick = stamp(tick);
    int runTick = givenTick + Standard1v1Battle.PLAY_DELAY_TICKS;
    int deckIndex = match.side(side).getHand().slots()[slot];
    if (deckIndex == Hand.EMPTY) {
      return refused(Refusal.NOT_IN_HAND, LadderMatch.NOT_IN_HAND, givenTick, runTick, 0);
    }
    if (isPending(side, deckIndex)) {
      return refused(Refusal.CARD_PENDING, SCREEN, givenTick, runTick, 0);
    }
    // The gates before the elixir: the match decided, the king dead, the card in the hand, the
    // start cooldown, read on the tick counter.
    int gate = match.gate(side, deckIndex, 0, tick);
    if (gate != 0) {
      return refused(playRefusal(gate), gate, givenTick, runTick, 0);
    }
    MatchCard card = match.side(side).deck().get(deckIndex);
    int[] promised = promisedSplit(side);
    Kind kind;
    int cost;
    DeployCard searchCard;
    DeployCard itemCard = null;
    int option = -1;
    if (card.mirror()) {
      kind = Kind.MIRROR;
      MirrorItem item = mirrorItem(side, deckIndex, level, name);
      cost = item.cost();
      // The screen's search is handed the Mirror, which it places as the king's last card, or
      // finds no tile for with none; the item the client builds may repeat a waiting play instead.
      MatchCard last = match.mirrorItem(side, deckIndex, level).repeats();
      searchCard = last == null ? null : records().card(last.name());
      if (item.repeats() != null && (last == null || !item.repeats().name().equals(last.name()))) {
        if (searchCard == null) {
          throw new UnsupportedOperationException(
              name
                  + ": a Mirror given while its king has no last card and its item repeats a"
                  + " listed play is not modelled: whether the screen's search is handed the"
                  + " king's last card, and finds no tile, is not traced");
        }
        itemCard = records().card(item.repeats().name());
      }
    } else if (card.variant() != null) {
      kind = Kind.VARIANT;
      option = match.pickOption(side, card.name(), promised[0]);
      if (promised[1] > 0
          && match.pickOption(side, card.name(), promised[0] + promised[1]) != option) {
        throw new UnsupportedOperationException(
            name
                + ": a variant card's option that the expiry of a refused play's promise decides,"
                + " at a tick not traced, is not modelled");
      }
      VariantItem item = match.variantItem(side, deckIndex, option);
      cost = item.cost();
      searchCard = records().card(item.spell());
    } else {
      kind = Kind.CARD;
      EvolutionItem item = match.item(side, deckIndex);
      cost = item.cost();
      // An evolved or hero play is placed as its row in that form.
      searchCard =
          item.field() != 0 ? records().card(item.spell().name()) : records().card(card.name());
    }
    boolean covered = covers(side, cost, promised[0]);
    if (promised[1] > 0 && covers(side, cost, promised[0] + promised[1]) != covered) {
      throw new UnsupportedOperationException(
          name
              + ": a play whose elixir check the expiry of a refused play's promise decides, at a"
              + " tick not traced, is not modelled");
    }
    if (!covered) {
      return refused(
          Refusal.NOT_ENOUGH_ELIXIR, LadderMatch.NOT_ENOUGH_ELIXIR, givenTick, runTick, cost);
    }
    if (searchCard == null) {
      // A Mirror whose king has no last card: the search finds no tile wherever it is dropped.
      return refused(Refusal.NO_TILE, SCREEN, givenTick, runTick, cost);
    }
    return new Tried(null, deckIndex, kind, cost, searchCard, option, itemCard);
  }

  /**
   * The item a Mirror's play carries as its client builds it at the giving: from the last play of
   * its side it lists as promised, waiting or refused at its run with its promise kept, else from
   * the king's copy of the last card. A waiting deck card's play is repeated with the item its side
   * builds for it now, a refused one with the item it ran with.
   */
  private MirrorItem mirrorItem(int side, int deckIndex, int level, String name) {
    LadderMatch match = battle.getMatch();
    Given source = null;
    Standing sourceStanding = Standing.RELEASED;
    for (Given command : given) {
      Standing standing = standing(command);
      if (command.side() == side
          && command.kind() != Kind.ABILITY
          && standing != Standing.RELEASED) {
        source = command;
        sourceStanding = standing;
      }
    }
    if (source == null) {
      return match.mirrorItem(side, deckIndex, level);
    }
    if (sourceStanding == Standing.DROPPING) {
      throw new UnsupportedOperationException(
          name
              + ": a Mirror given about the tick the promise of a play of its side refused at its"
              + " run is dropped, which the client may still repeat, is not modelled");
    }
    if (source.kind() != Kind.CARD) {
      throw new UnsupportedOperationException(
          name
              + ": a Mirror given while its client lists a Mirror's or variant card's play of its"
              + " side as promised, whose item it would repeat, is not modelled");
    }
    EvolutionItem repeated =
        sourceStanding == Standing.WAITING
            ? match.item(side, source.deckIndex())
            : playNamed(source.name()).evolution();
    return match.mirrorItemOf(side, deckIndex, level, repeated);
  }

  /**
   * The whole elixir a side's client has promised now: what it surely keeps, and what refused plays
   * whose promise is about to be dropped, at a tick not traced, may still keep.
   */
  private int[] promisedSplit(int side) {
    int sure = 0;
    int dropping = 0;
    for (Given command : given) {
      if (command.side() != side) {
        continue;
      }
      switch (standing(command)) {
        case WAITING, KEPT -> sure += command.cost();
        case DROPPING -> dropping += command.cost();
        default -> {}
      }
    }
    return new int[] {sure, dropping};
  }

  /** Where a command's promise stands now. */
  private Standing standing(Given command) {
    int tick = battle.getBattle().getTick();
    if (command.runTick() >= tick) {
      return Standing.WAITING;
    }
    // An ability command's run releases its promise before its gates, whatever it comes to.
    if (command.kind() == Kind.ABILITY) {
      return Standing.RELEASED;
    }
    Standard1v1Battle.Play play = findPlay(command.name());
    if (play == null) {
      // Due and not run, as in a step the match replaces: still waiting.
      return Standing.WAITING;
    }
    if (play.matchCode() == 0 && play.result() != null && play.result().placed()) {
      return Standing.RELEASED;
    }
    if (tick <= command.expiresTick() - DROP_WINDOW) {
      return Standing.KEPT;
    }
    return tick <= command.expiresTick() + DROP_WINDOW ? Standing.DROPPING : Standing.RELEASED;
  }

  /** Whether the king's whole elixir less what is set aside, never below 0, covers a cost. */
  private boolean covers(int side, int cost, int setAside) {
    return Math.max(battle.getMatch().side(side).wholeElixir() - setAside, 0) >= cost;
  }

  /** The unit of the side a play that has run made under a name, or null. */
  private CharacterEntity playedUnit(int side, String unit) {
    for (Standard1v1Battle.Play play : battle.getPlays()) {
      for (CharacterEntity made : play.units()) {
        if (made.name().equals(unit) && made.side() == side) {
          return made;
        }
      }
    }
    return null;
  }

  private Standard1v1Battle.Play findPlay(String name) {
    for (Standard1v1Battle.Play play : battle.getPlays()) {
      if (play.name().equals(name)) {
        return play;
      }
    }
    return null;
  }

  private Standard1v1Battle.Play playNamed(String name) {
    Standard1v1Battle.Play play = findPlay(name);
    checkArgument(play != null, () -> name + " has not run");
    return play;
  }

  private void checkNameNew(String name) {
    for (Given command : given) {
      checkArgument(!command.name().equals(name), () -> name + " is given already");
    }
  }

  private BattleRecords records() {
    return battle.getWorld().getRecords();
  }

  /** The stamp of a command given at a tick counter: the counter, or 1 while it is 0 or less. */
  private static int stamp(int tick) {
    return Math.max(tick, 1);
  }

  private static Tried refused(Refusal refusal, int code, int givenTick, int runTick, int cost) {
    return new Tried(
        new Giving(refusal, code, givenTick, runTick, cost), -1, null, cost, null, -1, null);
  }

  /** A match gate's code as a refusal. */
  private static Refusal playRefusal(int code) {
    return switch (code) {
      case LadderMatch.OVER -> Refusal.MATCH_OVER;
      case LadderMatch.KING_DEAD -> Refusal.KING_DEAD;
      case LadderMatch.NOT_IN_HAND -> Refusal.NOT_IN_HAND;
      case LadderMatch.START_COOLDOWN -> Refusal.START_COOLDOWN;
      case LadderMatch.NOT_ENOUGH_ELIXIR -> Refusal.NOT_ENOUGH_ELIXIR;
      default -> throw new IllegalStateException("a match gate's code " + code);
    };
  }

  /** An ability gate's code as a refusal. */
  private static Refusal abilityRefusal(int code) {
    return switch (code) {
      case AbilityCommand.KING_GONE -> Refusal.KING_DEAD;
      case AbilityCommand.NO_CHAMPION -> Refusal.NO_CHAMPION;
      case AbilityCommand.COOLDOWN -> Refusal.COOLDOWN;
      case AbilityCommand.NO_CHARGES -> Refusal.NO_CHARGES;
      case AbilityCommand.NOT_CHAMPION -> Refusal.NOT_CHAMPION;
      case AbilityCommand.PENDING -> Refusal.ABILITY_PENDING;
      case AbilityCommand.CLONE -> Refusal.CLONE;
      case AbilityCommand.FROZEN -> Refusal.FROZEN;
      case AbilityCommand.DISABLED -> Refusal.ABILITY_DISABLED;
      case AbilityCommand.ELIXIR -> Refusal.NOT_ENOUGH_ELIXIR;
      default -> throw new IllegalStateException("an ability gate's code " + code);
    };
  }
}
