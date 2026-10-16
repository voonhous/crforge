/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.Timeline;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.env.EnvFault.CoreFailure;

/**
 * A Ladder battle between two seats on the battle core, played as players play it: every play goes
 * through the battle's {@link PlayerCommands}, the client's own path, so a seat's play is tried as
 * the client tries it, sent on the tile the screen's placement search finds, promised, and run
 * {@link Schema#PLAY_DELAY_TICKS} after its stamp against the battle as it stands then, where the
 * match gates it and pays for it. The match decides the end. The environment adds nothing to the
 * battle: it reads the observation off the battle core's own objects after each decision.
 *
 * <p>The legality mask is what the player's screen offers: nothing but the wait before the match's
 * start cooldown; then a hand slot is open when the client's try of its card passes ({@link
 * PlayerCommands#tryCard}), and every tile of an open slot is open, as a drop anywhere is moved to
 * the tile the placement search finds. A play the mask let through may still be refused at its run,
 * as a player's can, and the seat is shown so.
 *
 * <p>An action the mask closed, out of range, or refused at its giving is taken as a wait and
 * counted as illegal, with its refusal code in the seat's header. A giving the battle core refuses
 * as not modelled (an {@link UnsupportedOperationException} from the player's commands) is refused
 * the same way, not a fault. Any other exception of the battle core is wrapped as a {@link
 * CoreFailure} for the vector's fault boundary.
 *
 * <p>The two seats' actions of a decision are given one after the other, and commands given on the
 * same tick run in the order they were given; so the side given first alternates: side 0 on even
 * decisions, counted from 0, side 1 on odd ones.
 */
public final class LadderEnv extends BufferedEnv {

  /**
   * A play this environment gave that has not run yet.
   *
   * @param name the play's name
   * @param side the giving side
   * @param card the vocabulary id of the hand card played
   * @param x the point its command carries, in game units
   * @param y the point its command carries, in game units
   * @param runTick the tick it runs on
   */
  private record Waiting(String name, int side, int card, int x, int y, int runTick) {}

  private final GameTables tables;
  private final Vocabulary vocabulary;
  private final EnvConfig config;

  private Standard1v1Battle standard;
  private Battle battle;
  private LadderMatch match;
  private PlayerCommands commands;

  /** The deck card each play this environment gave played, by the play's name. */
  private final Map<String, String> givenCards = new HashMap<>();

  private final List<Waiting> waiting = new ArrayList<>();
  private final List<List<String>> enemySeen = List.of(new ArrayList<>(), new ArrayList<>());

  /**
   * Per seat: its king, left and right princess tower, then the enemy's, left and right as seen.
   */
  private final TowerEntity[][] towers = new TowerEntity[2][6];

  private final int[] lastRefusal = new int[2];
  private final int[] lastRunRefusal = new int[2];
  private final int[] illegalNow = new int[2];
  private final int[] illegalTotal = new int[2];
  private int notModelled;
  private int firstSide;
  private int playsSeen;
  private int playsGiven;
  private int episodeStep;
  private boolean done;
  private boolean truncated;

  /**
   * An environment on a data version's tables.
   *
   * @param tables the game tables, shared read-only by every environment of the process
   * @param vocabulary the vocabulary of those tables
   * @param config how the battles are played
   */
  public LadderEnv(GameTables tables, Vocabulary vocabulary, EnvConfig config) {
    this.tables = tables;
    this.vocabulary = vocabulary;
    this.config = config;
  }

  /** The battle, for a test or a tool that reads more than the observation. */
  public Standard1v1Battle battle() {
    return standard;
  }

  /** The player's commands of the battle, for a test or a tool. */
  public PlayerCommands commands() {
    return commands;
  }

  @Override
  public void checkDeck(List<String> deck) {
    if (deck.size() != Schema.DECK_SIZE) {
      throw new IllegalArgumentException(
          "a deck of " + Schema.DECK_SIZE + " cards, got " + deck.size() + ": " + deck);
    }
    Set<String> seen = new HashSet<>();
    for (String card : deck) {
      if (card == null || !vocabulary.isDeckCard(card)) {
        throw new IllegalArgumentException(
            "the tables (" + vocabulary.dataVersion() + ") have no card named " + card);
      }
      if (!seen.add(card)) {
        throw new IllegalArgumentException("a deck holds " + card + " twice: " + deck);
      }
    }
  }

  @Override
  public void reset(int seed, List<String> deck0, List<String> deck1) {
    clearFault();
    standard = null;
    battle = null;
    match = null;
    commands = null;
    givenCards.clear();
    waiting.clear();
    enemySeen.get(0).clear();
    enemySeen.get(1).clear();
    Arrays.fill(lastRefusal, 0);
    Arrays.fill(lastRunRefusal, 0);
    Arrays.fill(illegalNow, 0);
    Arrays.fill(illegalTotal, 0);
    notModelled = 0;
    firstSide = 0;
    playsSeen = 0;
    playsGiven = 0;
    episodeStep = 0;
    done = false;
    truncated = false;
    clear();
    try {
      standard = new Standard1v1Battle(tables, config.towerLevel());
      standard.getWorld().seed(seed);
      match = standard.startLadderMatch(deck0, deck1, 0, 0);
      commands = new PlayerCommands(standard);
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    battle = standard.getBattle();
    rememberTowers();
    observe();
  }

  @Override
  public void step(int action0, int action1) {
    if (done) {
      throw new IllegalStateException("the episode is over: reset the environment");
    }
    firstSide = episodeStep % 2;
    Arrays.fill(illegalNow, 0);
    Arrays.fill(lastRunRefusal, 0);
    notModelled = 0;
    apply(firstSide, firstSide == 0 ? action0 : action1);
    apply(1 - firstSide, firstSide == 0 ? action1 : action0);
    for (int t = 0; t < config.decisionTicks() && !battle.getMode().isOver(); t++) {
      try {
        battle.step();
      } catch (RuntimeException | AssertionError | StackOverflowError e) {
        throw new CoreFailure(e);
      }
    }
    readPlays();
    episodeStep++;
    truncated =
        !battle.getMode().isOver()
            && config.maxTicks() > 0
            && battle.getTick() >= config.maxTicks();
    done = truncated || battle.getMode().isOver();
    observe();
  }

  @Override
  public boolean done() {
    return done;
  }

  @Override
  protected int faultTick() {
    return battle == null ? -1 : battle.getTick();
  }

  @Override
  protected int faultEpisodeStep() {
    return episodeStep;
  }

  @Override
  protected void ended() {
    done = true;
  }

  /**
   * Gives a seat's action to the battle through the player's commands: nothing for a wait, a play
   * for an action the seat's mask left open; an action refused is counted as illegal.
   */
  private void apply(int side, int action) {
    lastRefusal[side] = Schema.REFUSAL_NONE;
    if (action == Schema.WAIT) {
      return;
    }
    if (action < 1 || action >= Schema.ACTIONS || !open(side, action)) {
      refuse(side, Schema.REFUSAL_MASKED);
      return;
    }
    int slot = (action - 1) / Schema.TILES;
    int tile = (action - 1) % Schema.TILES;
    int x = Schema.gameX(side, tile % Schema.COLS);
    int y = Schema.gameY(side, tile / Schema.COLS);
    MatchSide own = match.side(side);
    int deckIndex = own.getHand().slots()[slot];
    String name = "s" + side + "_" + (++playsGiven);
    PlayerCommands.Giving giving;
    try {
      giving = commands.playCard(side, slot, config.level(), x, y, name);
    } catch (UnsupportedOperationException e) {
      // A giving the battle core does not model: nothing was sent, as for a refusal.
      notModelled++;
      refuse(side, Schema.REFUSAL_NOT_MODELLED);
      return;
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    if (!giving.given()) {
      refuse(side, Schema.refusalCode(giving.refusal()));
      return;
    }
    String card = own.deck().get(deckIndex).name();
    givenCards.put(name, card);
    waiting.add(
        new Waiting(name, side, vocabulary.id(card), giving.x(), giving.y(), giving.runTick()));
  }

  private void refuse(int side, int code) {
    lastRefusal[side] = code;
    illegalNow[side] = 1;
    illegalTotal[side]++;
  }

  /**
   * Reads the plays that ran since the last read: each leaves the waiting list; a play refused at
   * its run is shown to its seat, a placed one to the enemy's.
   */
  private void readPlays() {
    List<Standard1v1Battle.Play> plays = standard.getPlays();
    for (; playsSeen < plays.size(); playsSeen++) {
      Standard1v1Battle.Play play = plays.get(playsSeen);
      String card = givenCards.get(play.name());
      if (card == null) {
        continue;
      }
      waiting.removeIf(w -> w.name().equals(play.name()));
      int side = play.side();
      if (play.matchCode() != 0) {
        lastRunRefusal[side] = play.matchCode();
        continue;
      }
      if (play.result() == null || !play.result().placed()) {
        lastRunRefusal[side] = Schema.RUN_NOT_PLACED;
        continue;
      }
      List<String> seen = enemySeen.get(1 - side);
      see(seen, card);
      // A Mirror's play shows the card it repeated too.
      if (play.mirror() != null && play.mirror().repeats() != null) {
        see(seen, play.mirror().repeats().name());
      }
    }
  }

  private static void see(List<String> seen, String card) {
    if (!seen.contains(card) && seen.size() < Schema.DECK_SIZE) {
      seen.add(card);
    }
  }

  /**
   * Keeps each seat's towers: its own king, left and right princess tower, then the enemy's, left
   * and right as the seat's player sees them.
   */
  private void rememberTowers() {
    List<List<TowerEntity>> princess = List.of(new ArrayList<>(), new ArrayList<>());
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && !tower.getData().king()) {
        int side = tower.side();
        if (side != 0 && side != 1) {
          throw new IllegalStateException("a princess tower of side " + side);
        }
        princess.get(side).add(tower);
      }
    }
    for (int side = 0; side < 2; side++) {
      if (princess.get(side).size() != 2) {
        throw new IllegalStateException(
            "side " + side + " has " + princess.get(side).size() + " princess towers, not two");
      }
    }
    for (int seat = 0; seat < 2; seat++) {
      for (int owner = 0; owner < 2; owner++) {
        int at = owner == seat ? 0 : 3;
        TowerEntity a = princess.get(owner).get(0);
        TowerEntity b = princess.get(owner).get(1);
        boolean aLeft =
            Schema.toSeatX(seat, a.getView().getX()) <= Schema.toSeatX(seat, b.getView().getX());
        towers[seat][at] = standard.getWorld().kingTower(owner);
        towers[seat][at + 1] = aLeft ? a : b;
        towers[seat][at + 2] = aLeft ? b : a;
      }
    }
  }

  /** Writes both seats' observations and masks, the rewards and the info record. */
  private void observe() {
    for (int seat = 0; seat < 2; seat++) {
      clearSeatScratch();
      header(seat);
      entities(seat);
      flushSeat(seat);
      mask(seat);
      rewards.put(seat, reward(seat));
    }
    boolean over = battle.getMode().isOver();
    int winner = match.getWinner();
    info.put(Schema.I_TICK, battle.getTick());
    info.put(Schema.I_EPISODE_STEP, episodeStep);
    info.put(
        Schema.I_WINNER,
        !over ? Schema.WINNER_NONE : winner == 0 || winner == 1 ? winner : Schema.WINNER_DRAW);
    info.put(Schema.I_CROWNS_0, match.crowns(0));
    info.put(Schema.I_CROWNS_1, match.crowns(1));
    info.put(Schema.I_FAULT, Schema.FAULT_NONE);
    info.put(Schema.I_RESET, 0);
    info.put(Schema.I_ILLEGAL_0, illegalNow[0]);
    info.put(Schema.I_ILLEGAL_1, illegalNow[1]);
    info.put(Schema.I_TRUNCATED, truncated ? 1 : 0);
    info.put(Schema.I_FIRST_SIDE, firstSide);
    info.put(Schema.I_NOT_MODELLED, notModelled);
  }

  private float reward(int seat) {
    int result = result(seat);
    return result == Schema.WON ? 1f : result == Schema.LOST ? -1f : 0f;
  }

  private int result(int seat) {
    if (!battle.getMode().isOver()) {
      return Schema.RUNNING;
    }
    int winner = match.getWinner();
    return winner == seat ? Schema.WON : winner == 1 - seat ? Schema.LOST : Schema.DRAW;
  }

  private void header(int seat) {
    int[] o = seatScratch;
    MatchSide own = match.side(seat);
    Hand hand = own.getHand();
    int[] slots = hand.slots();
    int tick = battle.getTick();
    o[Schema.H_TICK] = tick;
    o[Schema.H_RESULT] = result(seat);
    o[Schema.H_ELIXIR] = own.getElixir();
    o[Schema.H_ELIXIR_RESERVED] = commands.promised(seat) * MatchSide.SCALE;
    o[Schema.H_CROWNS] = match.crowns(seat);
    o[Schema.H_CROWNS_ENEMY] = match.crowns(1 - seat);
    o[Schema.H_HAND_REFILL_MS] = hand.getCooldownMs();
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      int index = slots[k];
      if (index == Hand.EMPTY) {
        o[Schema.H_HAND_CARD + k] = -1;
        continue;
      }
      MatchCard card = own.deck().get(index);
      o[Schema.H_HAND_CARD + k] = vocabulary.id(card.name());
      o[Schema.H_HAND_COST + k] = card.cost();
      o[Schema.H_HAND_PENDING + k] = commands.isPending(seat, index) ? 1 : 0;
    }
    List<Integer> queue = hand.queue();
    o[Schema.H_NEXT_CARD] =
        queue.isEmpty() ? -1 : vocabulary.id(own.deck().get(queue.get(0)).name());
    List<String> seen = enemySeen.get(seat);
    for (int k = 0; k < Schema.DECK_SIZE; k++) {
      o[Schema.H_DECK + k] = k < own.deck().size() ? vocabulary.id(own.deck().get(k).name()) : -1;
      o[Schema.H_ENEMY_SEEN + k] = k < seen.size() ? vocabulary.id(seen.get(k)) : -1;
    }
    for (int t = 0; t < 6; t++) {
      TowerEntity tower = towers[seat][t];
      HitPoints points = tower.getHitPoints();
      o[Schema.H_TOWERS + 2 * t] = tower.getView().isAlive() ? points.getHitPoints() : 0;
      o[Schema.H_TOWERS + 2 * t + 1] = points.getMaximum();
    }
    o[Schema.H_DECISION_TICKS] = config.decisionTicks();
    o[Schema.H_PLAY_DELAY_TICKS] = Schema.PLAY_DELAY_TICKS;
    o[Schema.H_SEAT] = seat;
    o[Schema.H_EPISODE_STEP] = episodeStep;
    Timeline timeline = match.getTimeline();
    o[Schema.H_TIME_MS] = timeline.getTimeMs();
    o[Schema.H_SECTION] = timeline.getSection();
    o[Schema.H_SECTION_TYPE] = timeline.getSectionType();
    o[Schema.H_SECTION_LEFT_MS] = timeline.sectionLeftMs();
    o[Schema.H_FULL_BAR_MS] = timeline.getFullBarMs();
    o[Schema.H_OVERTIME] = timeline.overtime() ? 1 : 0;
    o[Schema.H_PLAY_OPEN_IN_TICKS] = Math.max(match.getStartCooldownTicks() - tick, 0);
    o[Schema.H_LAST_REFUSAL] = lastRefusal[seat];
    o[Schema.H_LAST_RUN_REFUSAL] = lastRunRefusal[seat];
    o[Schema.H_ILLEGAL] = illegalTotal[seat];
    int rows = 0;
    for (Waiting w : waiting) {
      if (w.side() != seat || rows == Schema.PENDING_ROWS) {
        continue;
      }
      int at = Schema.H_PENDING + rows * Schema.PENDING_INTS;
      o[at] = w.card();
      o[at + 1] = Schema.toSeatX(seat, w.x());
      o[at + 2] = Schema.toSeatY(seat, w.y());
      o[at + 3] = Math.max(w.runTick() - tick, 0);
      rows++;
    }
    o[Schema.H_PENDING_COUNT] = rows;
    for (int r = rows; r < Schema.PENDING_ROWS; r++) {
      o[Schema.H_PENDING + r * Schema.PENDING_INTS] = -1;
    }
  }

  /**
   * Writes the seat's entity rows: towers and characters in holder order, then area effects and
   * projectiles newest first, so a full list leaves out the oldest projectiles and area effects
   * before any character. Enemy characters that are hidden, invisible or underground are left out.
   */
  private void entities(int seat) {
    List<BattleEntity> list = battle.getHolder().entities();
    int rows = 0;
    int overflow = 0;
    for (BattleEntity entity : list) {
      if (entity instanceof WorldEntity world) {
        if (rows == Schema.MAX_ENTITIES) {
          overflow += visible(seat, world) ? 1 : 0;
        } else if (writeWorld(seat, rows, world)) {
          rows++;
        }
      }
    }
    for (int i = list.size() - 1; i >= 0; i--) {
      BattleEntity entity = list.get(i);
      if (entity instanceof WorldEntity) {
        continue;
      }
      if (rows == Schema.MAX_ENTITIES) {
        overflow++;
      } else {
        writeOther(seat, rows, entity);
        rows++;
      }
    }
    seatScratch[Schema.H_ENTITY_COUNT] = rows;
    seatScratch[Schema.H_ENTITY_OVERFLOW] = overflow;
  }

  /** Whether a seat's player sees a character or tower: not an enemy hidden from view. */
  private static boolean visible(int seat, WorldEntity world) {
    if (world.side() == seat || !(world instanceof CharacterEntity character)) {
      return true;
    }
    return !character.hidden() && !character.invisible() && !character.underground();
  }

  /** Writes a character or tower the seat sees at a row; false for one it does not see. */
  private boolean writeWorld(int seat, int row, WorldEntity world) {
    if (!visible(seat, world)) {
      return false;
    }
    int flags = 0;
    flags |= world.getView().isAlive() ? Schema.F_ALIVE : 0;
    flags |= world.getData().air() ? Schema.F_AIR : 0;
    flags |= world.getData().building() ? Schema.F_BUILDING : 0;
    flags |= world.getData().king() ? Schema.F_KING : 0;
    if (world instanceof CharacterEntity character) {
      flags |= character.hidden() ? Schema.F_HIDDEN : 0;
      flags |= character.invisible() ? Schema.F_INVISIBLE : 0;
      flags |= character.underground() ? Schema.F_UNDERGROUND : 0;
    }
    int at = Schema.HEADER_INTS + row * Schema.ENTITY_INTS;
    int[] o = seatScratch;
    o[at + Schema.E_ID] = world.getId();
    o[at + Schema.E_KIND] =
        world instanceof TowerEntity ? Schema.KIND_TOWER : Schema.KIND_CHARACTER;
    o[at + Schema.E_SIDE] = relative(seat, world.side());
    o[at + Schema.E_ROW] = vocabulary.id(world.getData().name());
    o[at + Schema.E_X] = Schema.toSeatX(seat, world.getView().getX());
    o[at + Schema.E_Y] = Schema.toSeatY(seat, world.getView().getY());
    HitPoints points = world.getHitPoints();
    if (points != null) {
      o[at + Schema.E_HP] = points.getHitPoints();
      o[at + Schema.E_MAX_HP] = points.getMaximum();
      o[at + Schema.E_SHIELD] = points.getShield();
    }
    o[at + Schema.E_STATE] = world.getView().getState();
    o[at + Schema.E_FLAGS] = flags;
    return true;
  }

  /** Writes a projectile or area effect at a row. */
  private void writeOther(int seat, int row, BattleEntity entity) {
    int kind;
    int side;
    int x;
    int y;
    String name;
    if (entity instanceof ProjectileEntity projectile) {
      kind = Schema.KIND_PROJECTILE;
      side = projectile.side();
      x = projectile.x();
      y = projectile.y();
      name = projectile.getData().name();
    } else if (entity instanceof AreaEffectEntity area) {
      kind = Schema.KIND_AREA;
      side = area.side();
      x = area.getX();
      y = area.getY();
      name = area.getData().name();
    } else {
      throw new IllegalStateException(
          "no encoding for an entity of " + entity.getClass().getSimpleName());
    }
    int at = Schema.HEADER_INTS + row * Schema.ENTITY_INTS;
    int[] o = seatScratch;
    o[at + Schema.E_ID] = entity.getId();
    o[at + Schema.E_KIND] = kind;
    o[at + Schema.E_SIDE] = relative(seat, side);
    o[at + Schema.E_ROW] = vocabulary.id(name);
    o[at + Schema.E_X] = Schema.toSeatX(seat, x);
    o[at + Schema.E_Y] = Schema.toSeatY(seat, y);
  }

  private static int relative(int seat, int side) {
    return side == seat ? 0 : side == 1 - seat ? 1 : 2;
  }

  /**
   * Writes a seat's mask: the wait, and once the match takes card plays, every tile of each hand
   * slot whose card the client's try passes. A try the battle core refuses as not modelled closes
   * its slot.
   */
  private void mask(int seat) {
    byte[] m = maskScratch;
    clearMaskScratch();
    m[Schema.WAIT] = 1;
    if (!done && battle.getTick() >= match.getStartCooldownTicks()) {
      for (int k = 0; k < Schema.HAND_SLOTS; k++) {
        PlayerCommands.Giving tried;
        try {
          tried = commands.tryCard(seat, k, config.level());
        } catch (UnsupportedOperationException e) {
          continue;
        } catch (RuntimeException | AssertionError | StackOverflowError e) {
          throw new CoreFailure(e);
        }
        if (tried.given()) {
          int from = 1 + k * Schema.TILES;
          Arrays.fill(m, from, from + Schema.TILES, (byte) 1);
        }
      }
    }
    flushMask(seat);
  }
}
