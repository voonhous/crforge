/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.io.IOException;
import java.nio.file.Path;
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
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.DeployMask;
import org.crforge.core.battle.deploy.MaskEntity;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.match.Timeline;
import org.crforge.core.battle.match.VariantItem;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.replay.ReplayBattle;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.unit.AbilityCommand;
import org.crforge.core.battle.unit.AbilityData;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.BuffComponent;
import org.crforge.core.battle.unit.BuffInstance;
import org.crforge.core.battle.unit.ChampionController;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.env.EnvFault.CoreFailure;

/**
 * A Ladder battle between two seats on the battle core, played as players play it: every play goes
 * through the battle's {@link PlayerCommands}, the client's own path, so a seat's play is tried as
 * the client tries it, sent on the tile the screen's placement search finds, promised, and run
 * {@link Schema#PLAY_DELAY_TICKS} after its stamp against the battle as it stands then, where the
 * match gates it and pays for it. The match decides the end. The environment adds nothing to the
 * battle: it reads the observation off the battle core's own objects after each decision.
 *
 * <p>The legality mask is what the player's screen offers: no play before the match's start
 * cooldown; then a hand slot is open when the client's try of its card passes ({@link
 * PlayerCommands#tryCard}), and of an open slot only the tiles its card lands on without being
 * moved (mask rule {@link Schema#MASK_LANDING_TILES}, the default): those whose centre the
 * placement search, on the deploy mask of the buildings and towers the seat sees, does not move.
 * Under rule {@link Schema#MASK_ALL_TILES} every tile of an open slot is open, as a drop anywhere
 * is moved to the tile the placement search finds. A champion slot's ability action is open when
 * the client's try of an ability command naming the slot's unit passes ({@link
 * PlayerCommands#tryAbility}); the unit is the one of the play the slot follows whose row names the
 * champion's ability, by the name the battle gave it. A play or ability command the mask let
 * through may still be refused at its run, as a player's can, and the seat is shown so.
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

  /**
   * An ability command this environment gave that has not run yet.
   *
   * @param name the command's name
   * @param side the giving side
   * @param slot the champion slot it was given for, 0 or 1
   * @param runTick the tick it runs on
   */
  private record AbilityWaiting(String name, int side, int slot, int runTick) {}

  /**
   * A play placed on the field, as the other side's player saw it run.
   *
   * @param card the vocabulary id of the deck card played
   * @param cast the vocabulary id of the row it was cast as
   * @param form its form: 0 plain, {@link Schema#FORM_EVOLVED} or {@link Schema#FORM_HERO}
   * @param tick the tick it ran on
   * @param x the point it was placed at, in game units
   * @param y the point it was placed at, in game units
   */
  private record SeenPlay(int card, int cast, int form, int tick, int x, int y) {}

  private final GameTables tables;
  private final Vocabulary vocabulary;
  private final EnvConfig config;

  /** The episode's battle seed and decks, as its reset gave them. */
  private int seed;

  private List<List<String>> decks = List.of(List.of(), List.of());

  /** The episode's slot flags by deck index, side 0's then side 1's, as its reset gave them. */
  private int[][] slots = new int[2][0];

  /**
   * Each side's card levels by deck index, for an episode reset on a replay's setup; null for every
   * card at {@link EnvConfig#level}.
   */
  private int[][] deckLevels;

  /**
   * Both seats' actions of each decision of the episode, seat 0's then seat 1's, kept for a fault
   * bundle only when bundles are written.
   */
  private int[] actionLog = new int[0];

  private int actionCount;

  private Standard1v1Battle standard;
  private Battle battle;
  private LadderMatch match;
  private PlayerCommands commands;

  /** The deck card each play this environment gave played, by the play's name. */
  private final Map<String, String> givenCards = new HashMap<>();

  private final List<Waiting> waiting = new ArrayList<>();
  private final List<List<String>> enemySeen = List.of(new ArrayList<>(), new ArrayList<>());
  private final List<AbilityWaiting> abilityWaiting = new ArrayList<>();

  /** Per side: its last plays placed, oldest first, at most {@link Schema#ENEMY_PLAY_ROWS}. */
  private final List<List<SeenPlay>> playsPlaced = List.of(new ArrayList<>(), new ArrayList<>());

  /** Per side: its plays placed in the episode. */
  private final int[] placedCount = new int[2];

  /** Per side: its elixir as the other side's player counts it. */
  private final ElixirCount[] elixirCounts = {new ElixirCount(), new ElixirCount()};

  /** The elixir gains the game shows both players, as the battle pays them. */
  private final ElixirCount.Gains shownGains = new ElixirCount.Gains();

  /** The plays and ability commands the elixir counts have read, and the most elixir. */
  private int playsCounted;

  private int abilityUsesCounted;
  private int maxElixir;

  /**
   * Per seat and entity kind, by the id's count within its kind ({@link
   * BattleEntity#IDS_PER_KIND}): where the seat's observation numbered {@code shownAt} showed the
   * entity, in the seat's frame, so a row gives the move since the seat's last observation.
   */
  private final int[][][] shownX = new int[2][0][];

  private final int[][][] shownY = new int[2][0][];
  private final int[][][] shownAt = new int[2][0][];

  /** Per seat: the ids its observation names entities by, in order of first sight. */
  private final SeatIds seatIds = new SeatIds();

  /** The number of the observation being written, counted over the environment's life. */
  private int observation;

  /** Per seat, the game grid's tile of each tile of the seat's frame. */
  private static final int[][] GAME_TILES = gameTiles();

  /**
   * Per seat: the characters its deploy masks were last built on, the alive buildings and king
   * towers it sees.
   */
  private final List<List<MaskEntity>> fieldSeen =
      new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>()));

  /** Per seat: the landing tiles of each card the screen's search placed, by its row name. */
  private final List<Map<String, boolean[]>> landingSeen =
      List.of(new HashMap<>(), new HashMap<>());

  /** Per seat and champion slot: the unit its ability action names, or null for none found. */
  private final CharacterEntity[][] abilityUnits = new CharacterEntity[2][Schema.ABILITY_SLOTS];

  /** Per seat and champion slot: the champion row the unit was found for. */
  private final UnitData[][] abilityChampions = new UnitData[2][Schema.ABILITY_SLOTS];

  /** Per seat and champion slot: the deploy count of the play the unit was found for. */
  private final int[][] abilityIndices = new int[2][Schema.ABILITY_SLOTS];

  /**
   * Per seat: its king, left and right princess tower, then the enemy's, left and right as seen.
   */
  private final TowerEntity[][] towers = new TowerEntity[2][6];

  private final int[] lastRefusal = new int[2];
  private final int[] lastRunRefusal = new int[2];
  private final int[] lastAbilityRunRefusal = new int[2];
  private final int[] illegalNow = new int[2];
  private final int[] illegalTotal = new int[2];
  private int notModelled;
  private int firstSide;
  private int playsSeen;
  private int abilityUsesSeen;
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

  /**
   * The name of the unit a seat's ability action of a champion slot names now, as the battle named
   * it, or null when the slot follows no play or no unit of the play is found: the unit of the play
   * the slot follows (its deploy count) whose row names the champion's ability, not a clone.
   *
   * @param seat the seat, 0 or 1
   * @param slot the champion slot, 0 or 1
   */
  public String abilityUnit(int seat, int slot) {
    CharacterEntity unit = findAbilityUnit(seat, slot);
    return unit == null ? null : unit.name();
  }

  /**
   * The battle id an id of a seat's observation stands for ({@link SeatIds}), or -1 for an id the
   * seat was not given in the episode.
   *
   * @param seat the seat, 0 or 1
   * @param id an id of the seat's observation: a row's, a target's or a champion slot's unit's
   */
  int battleId(int seat, int id) {
    return seatIds.battleId(seat, id);
  }

  @Override
  public void checkDeck(List<String> deck, int[] slots) {
    checkSlotFlags(slots);
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
  public void reset(int seed, List<String> deck0, List<String> deck1, int[] slots0, int[] slots1) {
    begin(seed, deck0, deck1, slots0, slots1, null);
    try {
      standard = new Standard1v1Battle(tables, config.towerLevel());
      standard.getWorld().seed(seed);
      // Each player's data is handed over before the decks are dealt, as the game's battle setup
      // does for every player: one choice each, so each draws once.
      standard.addPlayerData(1);
      standard.addPlayerData(1);
      match = standard.startLadderMatch(deck0, deck1, 0, 0, slots0, slots1);
      commands = new PlayerCommands(standard);
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    started();
  }

  /**
   * Starts an episode on a replay's setup, with none of its commands: the battle {@link
   * ReplayBattle#build} builds from the replay's translation (its seed, towers, each player's data,
   * the account words its deck shuffles draw with, the decks with their slot flags), and each play
   * of a deck card at the card's level as the replay lists it, not {@link EnvConfig#level}. The
   * commands are then given to it as a player's are, by {@link #step} or a replay's labeller.
   *
   * @param plan the replay's translation; its commands are left out
   */
  void resetReplay(ScenarioPlan plan) {
    begin(
        plan.seed(),
        plan.decks().get(0),
        plan.decks().get(1),
        plan.slotFlags().get(0),
        plan.slotFlags().get(1),
        new int[][] {plan.deckLevels().get(0).clone(), plan.deckLevels().get(1).clone()});
    ScenarioPlan setup =
        new ScenarioPlan(
            plan.seed(),
            plan.towers(),
            plan.decks(),
            plan.deckLevels(),
            plan.slotFlags(),
            plan.accounts(),
            plan.playerDataChoices(),
            List.of(),
            List.of());
    try {
      standard = ReplayBattle.build(tables, setup);
      match = standard.getMatch();
      commands = new PlayerCommands(standard);
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    started();
  }

  /** Forgets the last episode, before its battle is built. */
  private void begin(
      int seed,
      List<String> deck0,
      List<String> deck1,
      int[] slots0,
      int[] slots1,
      int[][] levels) {
    clearFault();
    this.seed = seed;
    this.decks = List.of(List.copyOf(deck0), List.copyOf(deck1));
    this.slots = new int[][] {slots0.clone(), slots1.clone()};
    this.deckLevels = levels;
    actionCount = 0;
    standard = null;
    battle = null;
    match = null;
    commands = null;
    givenCards.clear();
    waiting.clear();
    enemySeen.get(0).clear();
    enemySeen.get(1).clear();
    abilityWaiting.clear();
    playsPlaced.get(0).clear();
    playsPlaced.get(1).clear();
    Arrays.fill(placedCount, 0);
    playsCounted = 0;
    abilityUsesCounted = 0;
    // No row of the last episode is the previous observation of this one's first.
    observation += 2;
    seatIds.clear();
    for (int seat = 0; seat < 2; seat++) {
      fieldSeen.set(seat, new ArrayList<>());
      landingSeen.get(seat).clear();
      Arrays.fill(abilityUnits[seat], null);
      Arrays.fill(abilityChampions[seat], null);
      Arrays.fill(abilityIndices[seat], -1);
    }
    Arrays.fill(lastRefusal, 0);
    Arrays.fill(lastRunRefusal, 0);
    Arrays.fill(lastAbilityRunRefusal, 0);
    Arrays.fill(illegalNow, 0);
    Arrays.fill(illegalTotal, 0);
    notModelled = 0;
    firstSide = 0;
    playsSeen = 0;
    abilityUsesSeen = 0;
    episodeStep = 0;
    done = false;
    truncated = false;
    clear();
  }

  /** Reads the battle just built and writes the episode's first observation. */
  private void started() {
    battle = standard.getBattle();
    rememberTowers();
    // Both sides start at the game mode's starting elixir, which every player knows.
    int start = match.getTimeline().row().startingElixir() * MatchSide.SCALE;
    maxElixir = standard.getWorld().getKingElixir().maxMana();
    elixirCounts[0].start(start);
    elixirCounts[1].start(start);
    shownGains.clear();
    standard.getWorld().addObserver(shownGains);
    observe();
  }

  /**
   * The level a play of a side's deck card is given at: the card's level as the replay the episode
   * was reset on lists it, else {@link EnvConfig#level}.
   */
  private int level(int side, int deckIndex) {
    return deckLevels == null ? config.level() : deckLevels[side][deckIndex];
  }

  @Override
  public void step(int action0, int action1) {
    if (done) {
      throw new IllegalStateException("the episode is over: reset the environment");
    }
    if (config.faultBundles() != null) {
      logActions(action0, action1);
    }
    beginDecision();
    apply(firstSide, firstSide == 0 ? action0 : action1);
    apply(1 - firstSide, firstSide == 0 ? action1 : action0);
    for (int t = 0; t < config.decisionTicks() && !over(); t++) {
      advance();
    }
    endDecision();
  }

  /**
   * Opens a decision: the side given first, and the seats' refusals, illegal marks and run refusals
   * of the last decision cleared. {@link #step} opens each decision so; a replay's labeller opens
   * one, gives the replay's commands on their ticks, {@link #advance}s and ends it with {@link
   * #endDecision}.
   */
  void beginDecision() {
    firstSide = episodeStep % 2;
    Arrays.fill(lastRefusal, Schema.REFUSAL_NONE);
    Arrays.fill(illegalNow, 0);
    Arrays.fill(lastRunRefusal, 0);
    Arrays.fill(lastAbilityRunRefusal, 0);
    notModelled = 0;
  }

  /** Steps the battle one tick, and counts each side's elixir as the other's player does. */
  void advance() {
    try {
      battle.step();
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    countElixir();
  }

  /**
   * Counts the tick just stepped into each side's elixir as the other side's player sees it: the
   * plays placed and the champion abilities that ran in it, then the gains the game showed in it,
   * then the regeneration at the clock's rate. The battle takes the costs in its command pass,
   * before any gain or regeneration of the tick; the gains and the regeneration only add up to the
   * maximum, so their order among themselves does not move the sum.
   *
   * <p>The regeneration is the king's, in its step of the entity tick, so a step that ticks no
   * entities regenerates nothing: the step whose end timer reaches the end screen's delay, after
   * which the battle stops, and each tiebreaker step that runs no update. Whether the step ticked
   * the entities is the match's, the same on both players' screens, and says nothing of either
   * side's elixir.
   */
  private void countElixir() {
    List<Standard1v1Battle.Play> plays = standard.getPlays();
    for (; playsCounted < plays.size(); playsCounted++) {
      Standard1v1Battle.Play play = plays.get(playsCounted);
      if (play.result() == null || !play.result().placed()) {
        continue;
      }
      ElixirCount count = elixirCounts[play.side() & 1];
      if (play.mirror() != null) {
        MirrorItem mirror = play.mirror();
        count.play(
            mirror.cost(),
            mirror.repeats() == null ? 0 : mirror.repeats().elixirProductionStopTimeMs());
      } else if (play.variant() != null) {
        VariantItem variant = play.variant();
        count.play(variant.cost(), variant.elixirProductionStopTimeMs());
      } else if (play.evolution() != null) {
        EvolutionItem item = play.evolution();
        count.play(item.cost(), item.spell().elixirProductionStopTimeMs());
      }
    }
    List<Standard1v1Battle.AbilityUse> uses = standard.getAbilityUses();
    for (; abilityUsesCounted < uses.size(); abilityUsesCounted++) {
      Standard1v1Battle.AbilityUse use = uses.get(abilityUsesCounted);
      AbilityCommand.Outcome outcome = use.outcome();
      if (outcome.code() == AbilityCommand.OK) {
        // The ability's cost, as its row publishes it: what the player sees used.
        AbilityData ability = usedAbility(use);
        if (ability != null && ability.manaCost() >= 1) {
          elixirCounts[use.side() & 1].spend(ability.manaCost() * MatchSide.SCALE);
        }
      }
    }
    for (int side = 0; side < 2; side++) {
      elixirCounts[side].gain(shownGains.take(side), maxElixir);
    }
    if (!match.isLastTicked()) {
      return;
    }
    int fullBarMs = match.getTimeline().getFullBarMs();
    for (int side = 0; side < 2; side++) {
      elixirCounts[side].regenerate(fullBarMs, maxElixir);
    }
  }

  /**
   * The ability an ability command that passed was charged for: the battle charges the ability of
   * the unit the command named, whether or not its slot requested any copy (a form of the champion
   * on another row, such as the hero Wizard flying, is charged and requests none). The unit is the
   * one a play of the side made under the name the use lists; one named by a game object id no play
   * made falls back to the first copy requested. Null when neither is found.
   */
  private AbilityData usedAbility(Standard1v1Battle.AbilityUse use) {
    for (Standard1v1Battle.Play play : standard.getPlays()) {
      if (play.side() != use.side()) {
        continue;
      }
      for (CharacterEntity unit : play.units()) {
        if (unit.name().equals(use.unit())) {
          return unit.getData().ability();
        }
      }
    }
    List<CharacterEntity> requested = use.outcome().requested();
    return requested.isEmpty() ? null : requested.get(0).getData().ability();
  }

  /**
   * Side's elixir as the other side's player counts it, in ten-thousandths: the enemy elixir the
   * other seat's header shows. For a diagnostic of the count against the truth.
   *
   * @param side the side counted, 0 or 1
   */
  public int elixirCounted(int side) {
    return elixirCounts[side].elixir();
  }

  /** Whether the match is over. */
  boolean over() {
    return battle.getMode().isOver();
  }

  /**
   * Ends a decision: reads the plays and ability commands that ran in it, counts it, decides the
   * episode's end and writes the observation after it.
   */
  void endDecision() {
    readPlays();
    readAbilityUses();
    episodeStep++;
    truncated = !over() && config.maxTicks() > 0 && battle.getTick() >= config.maxTicks();
    done = truncated || over();
    observe();
  }

  @Override
  public boolean done() {
    return done;
  }

  @Override
  public boolean writeFaultBundle(Path folder, int index, Throwable thrown) throws IOException {
    FaultBundle.write(folder, this, index, thrown);
    return true;
  }

  /** The tables the battles are played on. */
  GameTables tables() {
    return tables;
  }

  /** How the battles are played. */
  EnvConfig config() {
    return config;
  }

  /** The episode's battle seed. */
  int seed() {
    return seed;
  }

  /** The episode's decks, side 0's then side 1's, by card row name. */
  List<List<String>> decks() {
    return decks;
  }

  /** The episode's slot flags by deck index, side 0's then side 1's. */
  int[][] slots() {
    return new int[][] {slots[0].clone(), slots[1].clone()};
  }

  /** The decisions taken in the episode. */
  int episodeStep() {
    return episodeStep;
  }

  /**
   * Both seats' actions of each decision of the episode, seat 0's then seat 1's, the step that
   * faulted last; empty when bundles are not written.
   */
  int[] actionLog() {
    return Arrays.copyOf(actionLog, actionCount);
  }

  private void logActions(int action0, int action1) {
    if (actionCount + 2 > actionLog.length) {
      actionLog = Arrays.copyOf(actionLog, Math.max(64, actionLog.length * 2));
    }
    actionLog[actionCount++] = action0;
    actionLog[actionCount++] = action1;
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
   * or an ability command for an action the seat's mask left open; an action refused is counted as
   * illegal.
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
    if (Schema.isAbility(action)) {
      useAbility(side, Schema.abilitySlot(action));
      return;
    }
    int slot = (action - 1) / Schema.TILES;
    int tile = (action - 1) % Schema.TILES;
    int x = Schema.gameX(side, tile % Schema.COLS);
    int y = Schema.gameY(side, tile / Schema.COLS);
    // Named as a replay of the battle names its commands, by their order: a fault's message reads
    // the same in the episode and in its bundle's replay.
    String name = "cmd" + standard.getCommands().size();
    PlayerCommands.Giving giving;
    try {
      giving = givePlay(side, slot, x, y, name);
    } catch (UnsupportedOperationException e) {
      // A giving the battle core does not model: nothing was sent, as for a refusal.
      notModelled++;
      refuse(side, Schema.REFUSAL_NOT_MODELLED);
      return;
    }
    if (!giving.given()) {
      refuse(side, Schema.refusalCode(giving.refusal()));
    }
  }

  /**
   * Gives a play of a hand slot's card dropped at a point through the player's commands, at the
   * card's level, and keeps it as waiting when given.
   *
   * @param side the playing side
   * @param slot the hand slot
   * @param x the dropped point in game units
   * @param y the dropped point in game units
   * @param name the command's name, new to the battle
   * @return what the giving came to
   * @throws UnsupportedOperationException for a giving the battle core does not model
   */
  PlayerCommands.Giving givePlay(int side, int slot, int x, int y, String name) {
    MatchSide own = match.side(side);
    int deckIndex = own.getHand().slots()[slot];
    PlayerCommands.Giving giving;
    try {
      giving =
          commands.playCard(
              side,
              slot,
              deckIndex == Hand.EMPTY ? config.level() : level(side, deckIndex),
              x,
              y,
              name);
    } catch (UnsupportedOperationException e) {
      throw e;
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    if (giving.given()) {
      trackPlay(
          name, side, own.deck().get(deckIndex).name(), giving.x(), giving.y(), giving.runTick());
    }
    return giving;
  }

  /**
   * Keeps a play queued on the battle as waiting, for the observation: its pending row until it
   * runs, and the card it shows the enemy when placed. {@link #givePlay} keeps each play it gives;
   * a replay's labeller keeps a recorded play it queues as the replay states it.
   *
   * @param name the play's name
   * @param side the playing side
   * @param card the deck card played, by row name
   * @param x the point its command carries
   * @param y the point its command carries
   * @param runTick the tick it runs on
   */
  void trackPlay(String name, int side, String card, int x, int y, int runTick) {
    givenCards.put(name, card);
    waiting.add(new Waiting(name, side, vocabulary.id(card), x, y, runTick));
  }

  /**
   * Keeps an ability command queued on the battle as waiting for a champion slot, for the slot's
   * row of the observation.
   *
   * @param name the command's name
   * @param side the commanding side
   * @param slot the champion slot, 0 or 1
   * @param runTick the tick it runs on
   */
  void trackAbility(String name, int side, int slot, int runTick) {
    abilityWaiting.add(new AbilityWaiting(name, side, slot, runTick));
  }

  /**
   * Gives an ability command naming the unit a champion slot's action names, through the player's
   * commands, as the player taps the slot's button.
   */
  private void useAbility(int side, int slot) {
    CharacterEntity unit = findAbilityUnit(side, slot);
    if (unit == null) {
      refuse(side, Schema.REFUSAL_MASKED);
      return;
    }
    // Named by its order among the battle's commands, as a replay names it.
    String name = "cmd" + standard.getCommands().size();
    PlayerCommands.Giving giving;
    try {
      giving = giveAbility(side, slot, unit.name(), name);
    } catch (UnsupportedOperationException e) {
      // A giving the battle core does not model: nothing was sent, as for a refusal.
      notModelled++;
      refuse(side, Schema.REFUSAL_NOT_MODELLED);
      return;
    }
    if (!giving.given()) {
      refuse(side, Schema.refusalCode(giving.refusal()));
    }
  }

  /**
   * Gives an ability command naming a unit through the player's commands, and keeps it as waiting
   * for its champion slot when given.
   *
   * @param side the commanding side
   * @param slot the champion slot whose button is tapped
   * @param unit the name of the unit it names
   * @param name the command's name, new to the battle
   * @return what the giving came to
   * @throws UnsupportedOperationException for a giving the battle core does not model
   */
  PlayerCommands.Giving giveAbility(int side, int slot, String unit, String name) {
    PlayerCommands.Giving giving;
    try {
      giving = commands.useAbility(side, unit, name);
    } catch (UnsupportedOperationException e) {
      throw e;
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    if (giving.given()) {
      trackAbility(name, side, slot, giving.runTick());
    }
    return giving;
  }

  /**
   * The unit a seat's ability action of a champion slot names now, or null: see {@link
   * #abilityUnit}.
   */
  CharacterEntity abilityEntity(int seat, int slot) {
    return findAbilityUnit(seat, slot);
  }

  /**
   * The unit a seat's ability action of a champion slot names: the unit of the play the slot
   * follows whose row names the champion's ability, not a clone; null when the slot follows no
   * play, or no play of the side that has run made such a unit (a champion an action spawned, which
   * the player's commands cannot name). Found once per play the slot follows.
   */
  private CharacterEntity findAbilityUnit(int seat, int slot) {
    ChampionController controller = standard.getWorld().kingTower(seat).championSlot(slot + 1);
    UnitData champion = controller == null ? null : controller.getChampion();
    int index = controller == null ? -1 : controller.getDeployIndex();
    if (champion == null || champion.ability() == null || index < 0) {
      return null;
    }
    if (abilityUnits[seat][slot] != null
        && abilityChampions[seat][slot] == champion
        && abilityIndices[seat][slot] == index) {
      return abilityUnits[seat][slot];
    }
    String ability = champion.ability().name();
    for (Standard1v1Battle.Play play : standard.getPlays()) {
      if (play.side() != seat) {
        continue;
      }
      for (CharacterEntity unit : play.units()) {
        AbilityData data = unit.getData().ability();
        if (unit.getDeployIndex() == index
            && !unit.isClone()
            && data != null
            && data.name().equals(ability)) {
          abilityUnits[seat][slot] = unit;
          abilityChampions[seat][slot] = champion;
          abilityIndices[seat][slot] = index;
          return unit;
        }
      }
    }
    return null;
  }

  /**
   * Reads the ability commands that ran since the last read: each leaves the waiting list, and one
   * refused at its run is shown to its seat with its gate's code.
   */
  private void readAbilityUses() {
    List<Standard1v1Battle.AbilityUse> uses = standard.getAbilityUses();
    for (; abilityUsesSeen < uses.size(); abilityUsesSeen++) {
      Standard1v1Battle.AbilityUse use = uses.get(abilityUsesSeen);
      if (abilityWaiting.removeIf(w -> w.name().equals(use.name()))
          && use.outcome().code() != AbilityCommand.OK) {
        lastAbilityRunRefusal[use.side()] = use.outcome().code();
      }
    }
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
      if (play.result() != null && play.result().placed()) {
        place(play);
      }
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

  /**
   * Keeps a placed play as the other side's player saw it run: the deck card, the row it was cast
   * as, its form and where it landed. A play outside a match carries no item and is not kept.
   */
  private void place(Standard1v1Battle.Play play) {
    int side = play.side() & 1;
    List<MatchCard> deck = match.side(side).deck();
    int index;
    String cast;
    int form = 0;
    if (play.mirror() != null) {
      index = play.mirror().index();
      cast = play.mirror().repeats() == null ? null : play.mirror().repeats().name();
    } else if (play.variant() != null) {
      index = play.variant().index();
      cast = play.variant().spell();
    } else if (play.evolution() != null) {
      index = play.evolution().index();
      cast = play.evolution().spell().name();
      form = play.evolution().field();
    } else {
      return;
    }
    int card = vocabulary.id(deck.get(index).name());
    List<SeenPlay> seen = playsPlaced.get(side);
    if (seen.size() == Schema.ENEMY_PLAY_ROWS) {
      seen.remove(0);
    }
    seen.add(
        new SeenPlay(
            card,
            cast == null ? card : vocabulary.id(cast),
            form,
            play.tick(),
            play.result().x(),
            play.result().y()));
    placedCount[side]++;
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
    observation++;
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
    for (int k = 0; k < Schema.QUEUE_SLOTS; k++) {
      o[Schema.H_QUEUE + k] =
          k < queue.size() ? vocabulary.id(own.deck().get(queue.get(k)).name()) : -1;
    }
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
    for (int k = 0; k < Schema.DECK_SIZE && k < own.deck().size(); k++) {
      o[Schema.H_DECK_SLOTS + k] = own.slotFlags(k);
      o[Schema.H_DECK_EVOLUTION + k] = own.evolutionCountUnknown(k) ? -1 : own.evolutionCount(k);
    }
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      int index = slots[k];
      o[Schema.H_HAND_FORM + k] = index == Hand.EMPTY ? 0 : own.item(index).field();
    }
    abilityRows(seat, tick);
    o[Schema.H_LAST_ABILITY_RUN_REFUSAL] = lastAbilityRunRefusal[seat];
    for (int k = 0; k < Schema.DECK_SIZE && k < own.deck().size(); k++) {
      o[Schema.H_DECK_LEVEL + k] = level(seat, k);
    }
    enemyRows(seat, tick);
  }

  /**
   * Writes what the seat's player saw of the enemy's plays: their count, the last ones, the elixir.
   */
  private void enemyRows(int seat, int tick) {
    int[] o = seatScratch;
    int enemy = 1 - seat;
    o[Schema.H_ENEMY_PLAY_COUNT] = placedCount[enemy];
    o[Schema.H_ENEMY_ELIXIR] = elixirCounts[enemy].elixir();
    List<SeenPlay> seen = playsPlaced.get(enemy);
    for (int r = 0; r < Schema.ENEMY_PLAY_ROWS; r++) {
      int at = Schema.H_ENEMY_PLAYS + r * Schema.ENEMY_PLAY_INTS;
      if (r >= seen.size()) {
        o[at] = -1;
        o[at + 1] = -1;
        continue;
      }
      SeenPlay play = seen.get(seen.size() - 1 - r);
      o[at] = play.card();
      o[at + 1] = play.cast();
      o[at + 2] = play.form();
      o[at + 3] = tick - play.tick();
      o[at + 4] = Schema.toSeatX(seat, play.x());
      o[at + 5] = Schema.toSeatY(seat, play.y());
    }
  }

  /** Writes the seat's champion slot rows. */
  private void abilityRows(int seat, int tick) {
    int[] o = seatScratch;
    TowerEntity king = standard.getWorld().kingTower(seat);
    for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
      int at = Schema.H_ABILITY + k * Schema.ABILITY_INTS;
      ChampionController slot = king.championSlot(k + 1);
      UnitData champion = slot == null ? null : slot.getChampion();
      CharacterEntity unit = findAbilityUnit(seat, k);
      o[at] = champion == null ? -1 : vocabulary.id(champion.name());
      o[at + 1] = unit != null && unit.getView().isAlive() ? seatIds.of(seat, unit.getId()) : -1;
      o[at + 2] = slot == null ? 0 : slot.getState();
      o[at + 3] = slot == null ? 0 : slot.getCooldownMs();
      o[at + 4] = slot == null ? 0 : slot.getCharges();
      int ticks = 0;
      for (AbilityWaiting w : abilityWaiting) {
        if (w.side() == seat && w.slot() == k) {
          ticks = Math.max(ticks, Math.max(w.runTick() - tick, 0));
        }
      }
      o[at + 5] = ticks;
    }
  }

  /**
   * Writes the seat's entity rows: towers and characters in holder order, then area effects and
   * projectiles newest first, so a full list leaves out the oldest projectiles and area effects
   * before any character. The characters and towers the seat does not see ({@link SeatView}) are
   * left out.
   */
  private void entities(int seat) {
    List<BattleEntity> list = battle.getHolder().entities();
    int rows = 0;
    int overflow = 0;
    for (BattleEntity entity : list) {
      if (entity instanceof WorldEntity world) {
        if (rows == Schema.MAX_ENTITIES) {
          overflow += SeatView.sees(seat, world) ? 1 : 0;
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

  /** Writes a character or tower the seat sees at a row; false for one it does not see. */
  private boolean writeWorld(int seat, int row, WorldEntity world) {
    if (!SeatView.sees(seat, world)) {
      return false;
    }
    GridEntity view = world.getView();
    EntityFlags bits = view.getFlagBits();
    long tags = view.getFlags();
    CharacterEntity character = world instanceof CharacterEntity c ? c : null;
    int flags = 0;
    flags |= view.isAlive() ? Schema.F_ALIVE : 0;
    flags |= world.getData().air() ? Schema.F_AIR : 0;
    flags |= world.getData().building() ? Schema.F_BUILDING : 0;
    flags |= world.getData().king() ? Schema.F_KING : 0;
    flags |= (tags & bits.noMove()) != 0 ? Schema.F_NO_MOVE : 0;
    flags |= (tags & bits.noAttack()) != 0 ? Schema.F_NO_ATTACK : 0;
    flags |= (tags & bits.charging()) != 0 ? Schema.F_CHARGED : 0;
    if (character != null) {
      flags |= character.hidden() ? Schema.F_HIDDEN : 0;
      flags |= character.invisible() ? Schema.F_INVISIBLE : 0;
      flags |= character.underground() ? Schema.F_UNDERGROUND : 0;
      flags |= character.isClone() ? Schema.F_CLONE : 0;
    } else if (world instanceof TowerEntity tower) {
      flags |= tower.isInactive() ? 0 : Schema.F_AWAKE;
    }
    int at = Schema.HEADER_INTS + row * Schema.ENTITY_INTS;
    int[] o = seatScratch;
    int x = Schema.toSeatX(seat, view.getX());
    int y = Schema.toSeatY(seat, view.getY());
    o[at + Schema.E_ID] = seatIds.of(seat, world.getId());
    o[at + Schema.E_KIND] =
        world instanceof TowerEntity ? Schema.KIND_TOWER : Schema.KIND_CHARACTER;
    o[at + Schema.E_SIDE] = relative(seat, world.side());
    o[at + Schema.E_ROW] = vocabulary.id(world.getData().name());
    o[at + Schema.E_X] = x;
    o[at + Schema.E_Y] = y;
    HitPoints points = world.getHitPoints();
    if (points != null) {
      o[at + Schema.E_HP] = points.getHitPoints();
      o[at + Schema.E_MAX_HP] = points.getMaximum();
      o[at + Schema.E_SHIELD] = points.getShield();
    }
    o[at + Schema.E_STATE] = view.getState();
    o[at + Schema.E_FLAGS] = flags;
    o[at + Schema.E_LEVEL] = world.level();
    moved(seat, at, world.getId(), x, y);
    TargetingState targeting = world.getTargeting();
    TargetView reference = targeting.getReference();
    target(
        seat, at, reference == null ? null : standard.getWorld().entityOf(reference.getEntity()));
    o[at + Schema.E_DEPLOY_MS] = character != null ? view.getDeployCountdown() : 0;
    o[at + Schema.E_ATTACK_MS] = targeting.getAttackTimerMs();
    o[at + Schema.E_LOAD_MS] = targeting.getLoadTimerMs();
    BuffComponent buffs = world.getBuffs();
    o[at + Schema.E_SPEED_PERCENT] = buffs.speed(100);
    o[at + Schema.E_HIT_SPEED_PERCENT] = buffs.hitSpeed(100);
    List<BuffInstance> items = buffs.items();
    o[at + Schema.E_BUFF_COUNT] = items.size();
    for (int b = 0; b < Schema.BUFF_SLOTS; b++) {
      int slot = at + Schema.E_BUFFS + 2 * b;
      if (b < items.size()) {
        BuffInstance item = items.get(b);
        o[slot] = vocabulary.buffId(item.getBuff().name());
        o[slot + 1] = item.getRemaining();
      } else {
        o[slot] = -1;
      }
    }
    return true;
  }

  /**
   * Writes the move of an entity since the seat's last observation, and keeps where this one shows
   * it: 0, 0 for an entity that observation did not show.
   */
  private void moved(int seat, int at, int id, int x, int y) {
    int kind = id / BattleEntity.IDS_PER_KIND;
    int n = id % BattleEntity.IDS_PER_KIND;
    if (kind >= shownAt[seat].length) {
      shownAt[seat] = Arrays.copyOf(shownAt[seat], kind + 1);
      shownX[seat] = Arrays.copyOf(shownX[seat], kind + 1);
      shownY[seat] = Arrays.copyOf(shownY[seat], kind + 1);
    }
    int[] seen = shownAt[seat][kind];
    if (seen == null || n >= seen.length) {
      int size = Math.max(n + 1, seen == null ? 64 : seen.length * 2);
      shownAt[seat][kind] = seen = seen == null ? new int[size] : Arrays.copyOf(seen, size);
      shownX[seat][kind] = copyOf(shownX[seat][kind], size);
      shownY[seat][kind] = copyOf(shownY[seat][kind], size);
    }
    if (seen[n] == observation - 1) {
      seatScratch[at + Schema.E_DX] = x - shownX[seat][kind][n];
      seatScratch[at + Schema.E_DY] = y - shownY[seat][kind][n];
    }
    seen[n] = observation;
    shownX[seat][kind][n] = x;
    shownY[seat][kind][n] = y;
  }

  private static int[] copyOf(int[] values, int size) {
    return values == null ? new int[size] : Arrays.copyOf(values, size);
  }

  /**
   * Writes a row's target: the seat's id of it and its position in the seat's frame, when the seat
   * sees it.
   */
  private void target(int seat, int at, WorldEntity target) {
    if (target == null || !SeatView.sees(seat, target)) {
      seatScratch[at + Schema.E_TARGET] = Schema.NO_TARGET;
      return;
    }
    seatScratch[at + Schema.E_TARGET] = seatIds.of(seat, target.getId());
    seatScratch[at + Schema.E_TARGET_X] = Schema.toSeatX(seat, target.getView().getX());
    seatScratch[at + Schema.E_TARGET_Y] = Schema.toSeatY(seat, target.getView().getY());
  }

  /** Writes a projectile or area effect at a row. */
  private void writeOther(int seat, int row, BattleEntity entity) {
    int at = Schema.HEADER_INTS + row * Schema.ENTITY_INTS;
    int[] o = seatScratch;
    int kind;
    int side;
    int x;
    int y;
    String name;
    int flags = Schema.F_ALIVE;
    if (entity instanceof ProjectileEntity projectile) {
      kind = Schema.KIND_PROJECTILE;
      side = projectile.side();
      x = projectile.x();
      y = projectile.y();
      name = projectile.getData().name();
      o[at + Schema.E_LEVEL] = PackedLevel.level(projectile.getPackedLevel());
      o[at + Schema.E_AIM_X] = Schema.toSeatX(seat, projectile.getAimX());
      o[at + Schema.E_AIM_Y] = Schema.toSeatY(seat, projectile.getAimY());
      target(seat, at, projectile.getTarget());
    } else if (entity instanceof AreaEffectEntity area) {
      kind = Schema.KIND_AREA;
      side = area.side();
      x = area.getX();
      y = area.getY();
      name = area.getData().name();
      flags |= area.isClone() ? Schema.F_CLONE : 0;
      o[at + Schema.E_LEVEL] = PackedLevel.level(area.getPackedLevel());
      o[at + Schema.E_TIME_LEFT_MS] = area.getCountdown();
      o[at + Schema.E_RADIUS] = area.radius();
      o[at + Schema.E_TARGET] = Schema.NO_TARGET;
    } else {
      throw new IllegalStateException(
          "no encoding for an entity of " + entity.getClass().getSimpleName());
    }
    int seatX = Schema.toSeatX(seat, x);
    int seatY = Schema.toSeatY(seat, y);
    o[at + Schema.E_ID] = seatIds.of(seat, entity.getId());
    o[at + Schema.E_KIND] = kind;
    o[at + Schema.E_SIDE] = relative(seat, side);
    o[at + Schema.E_ROW] = vocabulary.id(name);
    o[at + Schema.E_X] = seatX;
    o[at + Schema.E_Y] = seatY;
    o[at + Schema.E_STATE] = Schema.NO_STATE;
    o[at + Schema.E_FLAGS] = flags;
    moved(seat, at, entity.getId(), seatX, seatY);
    for (int b = 0; b < Schema.BUFF_SLOTS; b++) {
      o[at + Schema.E_BUFFS + 2 * b] = -1;
    }
  }

  private static int relative(int seat, int side) {
    return side == seat ? 0 : side == 1 - seat ? 1 : 2;
  }

  /**
   * Writes a seat's mask: the wait; once the match takes card plays, of each hand slot whose card
   * the client's try passes, the tiles its card lands on unmoved (mask rule {@link
   * Schema#MASK_LANDING_TILES}) or every tile (rule {@link Schema#MASK_ALL_TILES}); and each
   * champion slot's ability action whose command's try passes. A try the battle core refuses as not
   * modelled closes its action.
   */
  private void mask(int seat) {
    byte[] m = maskScratch;
    clearMaskScratch();
    m[Schema.WAIT] = 1;
    boolean landing = config.maskVersion() == Schema.MASK_LANDING_TILES;
    if (!done && !landing && battle.getTick() >= match.getStartCooldownTicks()) {
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
    if (!done && landing && battle.getTick() >= match.getStartCooldownTicks()) {
      seeField(seat);
      for (int k = 0; k < Schema.HAND_SLOTS; k++) {
        boolean[] lands;
        try {
          lands = landingTiles(seat, commands.searchCards(seat, k, config.level()));
        } catch (UnsupportedOperationException e) {
          continue;
        } catch (RuntimeException | AssertionError | StackOverflowError e) {
          throw new CoreFailure(e);
        }
        if (lands == null) {
          continue;
        }
        int from = 1 + k * Schema.TILES;
        int[] tiles = GAME_TILES[seat];
        for (int t = 0; t < Schema.TILES; t++) {
          m[from + t] = lands[tiles[t]] ? (byte) 1 : 0;
        }
      }
    }
    // The match does not gate an ability command on its start cooldown: no champion stands before.
    for (int k = 0; k < Schema.ABILITY_SLOTS && !done; k++) {
      CharacterEntity unit = findAbilityUnit(seat, k);
      if (unit == null) {
        continue;
      }
      PlayerCommands.Giving tried;
      try {
        tried = commands.tryAbility(seat, unit.name());
      } catch (UnsupportedOperationException e) {
        continue;
      } catch (RuntimeException | AssertionError | StackOverflowError e) {
        throw new CoreFailure(e);
      }
      if (tried.given()) {
        m[Schema.ability(k)] = 1;
      }
    }
    flushMask(seat);
  }

  /**
   * Reads the characters the seat's deploy masks are built on now: those the placement search reads
   * (alive buildings and king towers) that the seat sees, so an enemy building hidden from view
   * closes no tile. When they differ from the last decision's, the landing tiles kept for the seat
   * are dropped.
   */
  private void seeField(int seat) {
    List<MaskEntity> now = new ArrayList<>();
    try {
      for (MaskEntity e : standard.placementEntities(w -> SeatView.sees(seat, w))) {
        if (DeployMask.reads(e)) {
          now.add(e);
        }
      }
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      throw new CoreFailure(e);
    }
    if (!now.equals(fieldSeen.get(seat))) {
      fieldSeen.set(seat, now);
      landingSeen.get(seat).clear();
    }
  }

  /**
   * The tiles of the game's grid, row after row, a play of a hand slot lands on unmoved, from the
   * cards the screen's search is handed for it: a tile is open when every card lands on it. Null
   * when the client's try refuses the play. Kept per seat and card until the field the seat sees
   * changes.
   */
  private boolean[] landingTiles(int seat, List<DeployCard> cards) {
    if (cards.isEmpty()) {
      return null;
    }
    TileMap map = standard.getWorld().getTileMap();
    List<MaskEntity> field = fieldSeen.get(seat);
    boolean[] lands = null;
    for (DeployCard card : cards) {
      boolean[] own =
          landingSeen
              .get(seat)
              .computeIfAbsent(
                  card.name(),
                  name ->
                      PlacementSearch.landingTiles(
                          map, card, PlacementSearch.mask(map, card, seat, field)));
      if (lands == null) {
        lands = own;
      } else {
        // A Mirror whose item repeats a listed play: the giving searches both cards.
        boolean[] both = lands.clone();
        for (int t = 0; t < both.length; t++) {
          both[t] &= own[t];
        }
        lands = both;
      }
    }
    return lands;
  }

  /** Per seat, the game grid's tile of each tile of the seat's frame, row after row. */
  private static int[][] gameTiles() {
    int[][] tiles = new int[2][Schema.TILES];
    for (int seat = 0; seat < 2; seat++) {
      for (int row = 0; row < Schema.ROWS; row++) {
        for (int col = 0; col < Schema.COLS; col++) {
          int gameCol = Schema.gameX(seat, col) / Schema.TILE_UNITS;
          int gameRow = Schema.gameY(seat, row) / Schema.TILE_UNITS;
          tiles[seat][row * Schema.COLS + col] = gameRow * Schema.COLS + gameCol;
        }
      }
    }
    return tiles;
  }
}
