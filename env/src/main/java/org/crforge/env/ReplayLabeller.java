/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.replay.ReplayBattle;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.ScenarioShape;
import org.crforge.core.battle.replay.UnsupportedScenarioException;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.env.EnvFault.CoreFailure;

/**
 * Labels a replay for behavioural training: steps the replay's battle and writes, at every decision
 * of each seat, the observation and mask {@link LadderEnv} shows that seat, and the action that
 * gives the command the player gave in the decision's window, or the wait. It holds no data: it
 * labels any replay of the tables' data version.
 *
 * <p><b>The battle.</b> The replay's battle is built as {@link ReplayBattle#build} builds it, but
 * with none of its commands queued: each command is given to it on its given tick, the tick counter
 * it was stamped with, through the player's commands ({@link PlayerCommands}), from the point the
 * replay records, at the level its deck lists. So the client's view at each decision (the promised
 * elixir, the plays waiting, the champion buttons) is the one {@link LadderEnv} keeps for its own
 * plays. A command the client's try refuses at its giving, or whose recorded point the screen's
 * search would move, is queued as the replay states it instead, so the battle stays the replay's;
 * each is counted.
 *
 * <p><b>The alignment.</b> Decisions fall every {@code decisionTicks} ticks from tick 0, as {@link
 * LadderEnv} takes them. A command belongs to the decision whose window, from its tick to the next
 * decision's, holds its given tick; never its run tick. The row of a decision holds the observation
 * and mask written at its tick, before any command of its window is given.
 *
 * <p><b>The label.</b> A card play is labelled with the hand slot its card holds in the row's
 * observation and the tile of the seat's frame whose centre the screen's placement search, run on
 * the battle as it stands at the giving, sends to the point the replay records: a replay records
 * the tile the search found, a tile centre or a building's tile corner. Under the landing mask rule
 * ({@link Schema#MASK_LANDING_TILES}, the default) the tile the card landed on unmoved is tried
 * first, the tile of the game's grid holding the point, so a tap the screen moved is labelled with
 * the tile it landed on, which the row's mask opens; then, and under the rule of every tile first,
 * the tile of the seat's frame holding the point, then the others touching it. An ability command
 * is labelled with the ability action of the champion slot whose unit it names. A seat's first
 * command of a window gives its label; a second one in the window is counted as a collision ({@link
 * #COLLISION}). A command the action space cannot say at the row is labelled the wait and flagged
 * ({@link #INEXPRESSIBLE}): its card not in the row's hand, its unit no champion slot's, or a
 * variant card's option other than the one the client would pick now. Nothing is dropped: each
 * command is a label or a count.
 */
public final class ReplayLabeller {

  /** A row flag: the label's action is closed in the row's mask. */
  public static final int LABEL_MASKED = 1;

  /** A row flag: the seat gave another command in the window, which the label does not say. */
  public static final int COLLISION = 2;

  /** A row flag: the seat gave a command the action space cannot say at the row: labelled wait. */
  public static final int INEXPRESSIBLE = 4;

  /** A row flag: no tile centre's search gives the recorded point; the tile holding it is named. */
  public static final int POINT_UNMATCHED = 8;

  /**
   * A row flag: a command of the seat's window was queued as the replay states it, not given
   * through the player's commands: their try refused it, their search would move its point, or the
   * battle core does not model its giving.
   */
  public static final int GIVEN_AS_RECORDED = 16;

  /**
   * A row flag: a command of the seat queued as the replay states it waits to run, so the row's
   * promised elixir, pending marks and mask leave out what the player's client held back for it.
   */
  public static final int UNPROMISED = 32;

  /** The count names, in the order {@link Counts#asMap()} lists them. */
  public static final List<String> COUNT_NAMES =
      List.of(
          "decisions",
          "rows",
          "commands",
          "plays",
          "abilities",
          "labelled",
          "label_masked",
          "collisions",
          "inexpressible",
          "not_in_hand",
          "ability_unit_unknown",
          "option_differs",
          "point_unmatched",
          "given_as_recorded",
          "refused_at_giving",
          "point_moved",
          "not_modelled_at_giving",
          "after_end",
          "order_differs",
          "rows_unpromised");

  /**
   * What became of a replay's commands, and of its rows.
   *
   * @param decisions the decisions labelled, two rows each
   * @param rows the rows written
   * @param commands the replay's commands
   * @param plays its card plays
   * @param abilities its ability commands
   * @param labelled the commands that became a row's label (an inexpressible one is not)
   * @param labelMasked the labels the row's mask closes
   * @param collisions the commands left unlabelled because their seat's label of the window is
   *     another command's
   * @param inexpressible the commands that would have been their seat's label of a window but the
   *     action space cannot say at the row: the three counts below
   * @param notInHand a play whose card is not in the row's hand
   * @param abilityUnitUnknown an ability command whose unit no champion slot of its seat follows
   * @param optionDiffers a variant card's play whose recorded option the client would not pick now
   * @param pointUnmatched a labelled play whose recorded point no tile centre's search gives
   * @param givenAsRecorded the commands queued as the replay states them: the three counts below
   * @param refusedAtGiving the client's try refused it at its giving, its card was not in the hand,
   *     or, an ability command, no champion slot names its unit
   * @param pointMoved the screen's search would move its recorded point
   * @param notModelledAtGiving the battle core does not model its giving
   * @param afterEnd the commands given on or after the tick the labelling stopped at
   * @param orderDiffers the windows where both seats are labelled and the replay gave the two in
   *     the other order than {@link LadderEnv} gives two seats' actions (side 0 first on even
   *     decisions): a battle driven from the labels runs them in its own order
   * @param rowsUnpromised the rows flagged {@link #UNPROMISED}
   */
  public record Counts(
      int decisions,
      int rows,
      int commands,
      int plays,
      int abilities,
      int labelled,
      int labelMasked,
      int collisions,
      int inexpressible,
      int notInHand,
      int abilityUnitUnknown,
      int optionDiffers,
      int pointUnmatched,
      int givenAsRecorded,
      int refusedAtGiving,
      int pointMoved,
      int notModelledAtGiving,
      int afterEnd,
      int orderDiffers,
      int rowsUnpromised) {

    /** The counts by name ({@link #COUNT_NAMES}), in that order. */
    public Map<String, Integer> asMap() {
      int[] values = {
        decisions,
        rows,
        commands,
        plays,
        abilities,
        labelled,
        labelMasked,
        collisions,
        inexpressible,
        notInHand,
        abilityUnitUnknown,
        optionDiffers,
        pointUnmatched,
        givenAsRecorded,
        refusedAtGiving,
        pointMoved,
        notModelledAtGiving,
        afterEnd,
        orderDiffers,
        rowsUnpromised
      };
      Map<String, Integer> map = new LinkedHashMap<>();
      for (int i = 0; i < values.length; i++) {
        map.put(COUNT_NAMES.get(i), values[i]);
      }
      return map;
    }
  }

  /**
   * A replay's rows: decision after decision, seat 0's row then seat 1's.
   *
   * @param decisionTicks the decision interval the rows were taken at
   * @param rows how many rows
   * @param observations each row's observation, {@link Schema#SEAT_INTS} integers a row
   * @param masks each row's mask, {@link Schema#ACTIONS} bytes a row
   * @param actions each row's label, an action of the schema
   * @param ticks each row's decision tick
   * @param seats each row's seat, the game side it plays
   * @param flags each row's flags ({@link #LABEL_MASKED}, {@link #COLLISION}, {@link
   *     #INEXPRESSIBLE}, {@link #POINT_UNMATCHED}, {@link #GIVEN_AS_RECORDED})
   * @param counts what became of the replay's commands
   * @param stopTick the tick the labelling stopped at: the match's end, the replay's {@code
   *     endTick}, or the refusal's tick
   * @param refusal why the labelling stopped before the replay's end, or null: the rows before it
   *     stand, the battle after it is not the replay's
   */
  public record Labels(
      int decisionTicks,
      int rows,
      int[] observations,
      byte[] masks,
      int[] actions,
      int[] ticks,
      int[] seats,
      int[] flags,
      Counts counts,
      int stopTick,
      String refusal) {

    /** The counts as JSON, by name, with the stop tick and the refusal. */
    public String countsJson() {
      Map<String, Object> map = new LinkedHashMap<>(counts.asMap());
      map.put("decision_ticks", decisionTicks);
      map.put("stop_tick", stopTick);
      map.put("refusal", refusal);
      try {
        return MAPPER.writeValueAsString(map);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final GameTables tables;
  private final Vocabulary vocabulary;
  private final EnvConfig config;
  private final ScenarioShape shape;

  /**
   * A labeller of replays read in a scenario shape.
   *
   * @param tables the tables of the replays' data version
   * @param vocabulary the vocabulary of those tables
   * @param decisionTicks the decision interval, as {@link EnvConfig#decisionTicks}
   * @param shape the kind of scenario read: {@link ScenarioShape#REPLAY} for a replay the game
   *     client records, {@link ScenarioShape#GENERATED} for a generated case
   */
  public ReplayLabeller(
      GameTables tables, Vocabulary vocabulary, int decisionTicks, ScenarioShape shape) {
    this(tables, vocabulary, EnvConfig.DEFAULT.withDecisionTicks(decisionTicks), shape);
  }

  private ReplayLabeller(
      GameTables tables, Vocabulary vocabulary, EnvConfig config, ScenarioShape shape) {
    this.tables = tables;
    this.vocabulary = vocabulary;
    this.config = config;
    this.shape = shape;
  }

  /**
   * This labeller writing its rows' masks under another mask rule, as {@link
   * EnvConfig#maskVersion}: {@link Schema#MASK_LANDING_TILES}, the default, or {@link
   * Schema#MASK_ALL_TILES}.
   */
  public ReplayLabeller withMaskVersion(int version) {
    return new ReplayLabeller(tables, vocabulary, config.withMaskVersion(version), shape);
  }

  /** The mask rule the rows' masks follow. */
  public int maskVersion() {
    return config.maskVersion();
  }

  /**
   * A labeller of replays the game client records.
   *
   * @param tables the tables of the replays' data version
   * @param decisionTicks the decision interval
   */
  public ReplayLabeller(GameTables tables, int decisionTicks) {
    this(tables, VectorEnv.vocabulary(tables), decisionTicks, ScenarioShape.REPLAY);
  }

  /**
   * A labeller of a scenario shape by name ({@code replay} or {@code generated}), for a caller that
   * holds no enum.
   */
  public static ReplayLabeller of(GameTables tables, int decisionTicks, String shape) {
    return new ReplayLabeller(
        tables, VectorEnv.vocabulary(tables), decisionTicks, ScenarioShape.of(shape));
  }

  /**
   * A labeller of a scenario shape by name whose rows' masks follow a mask rule ({@link
   * #withMaskVersion}), for a caller that holds no enum.
   */
  public static ReplayLabeller of(
      GameTables tables, int decisionTicks, String shape, int maskVersion) {
    return of(tables, decisionTicks, shape).withMaskVersion(maskVersion);
  }

  /** The decision interval. */
  public int decisionTicks() {
    return config.decisionTicks();
  }

  /** The environment's metadata of these tables and this interval ({@link VectorEnv}). */
  public String metadataJson() {
    return VectorEnv.metadataJson(config, vocabulary);
  }

  /**
   * Labels a replay given as JSON text.
   *
   * @param replay the replay document
   * @return its rows and counts; none, with the refusal, for a replay that cannot be read
   */
  public Labels label(String replay) {
    try {
      return label(MAPPER.readTree(replay));
    } catch (IOException e) {
      return refused("not JSON: " + e.getMessage());
    }
  }

  /**
   * Labels a replay.
   *
   * @param replay the replay document
   * @return its rows and counts; none, with the refusal, for a replay that cannot be read
   */
  public Labels label(JsonNode replay) {
    ScenarioPlan plan;
    try {
      plan = new ReplayScenario(tables, shape).translate(replay);
    } catch (UnsupportedScenarioException e) {
      return refused("unsupported: " + e.feature() + " (" + e.input() + ")");
    } catch (RuntimeException e) {
      return refused("unreadable: " + e.getMessage());
    }
    return new Run(plan, replay.path("endTick").asInt(-1)).label();
  }

  private Labels refused(String reason) {
    int[] none = new int[0];
    return new Labels(
        config.decisionTicks(),
        0,
        none,
        new byte[0],
        none,
        none,
        none,
        none,
        new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        0,
        reason);
  }

  /**
   * What a battle driven from a replay's labels alone came to, against the replay's own battle
   * ({@link ReplayBattle#build}): the proof that the labels say the replay's commands.
   *
   * <p>The drive stops at the first command that differs but for its point, the first item that
   * differs and the first state that differs. A row that differs, or a command whose point alone
   * differs, is counted and the drive goes on: a point the action space's tile centre does not send
   * can still be placed where the replay's was, and the state then says so.
   *
   * @param decisions the decisions driven
   * @param rowsMatched the rows whose observation and mask the driven environment wrote the same as
   *     the labels' rows
   * @param commands the commands the driven battle gave
   * @param replayCommands the replay's commands given before the labels' stop tick
   * @param matchedCommands the replay's commands the driven battle gave the same in every field
   *     (kind, side, card, level, given and run tick, point, option, the unit an ability command
   *     names), checked in order after each decision
   * @param pointsDiffer the replay's commands the driven battle gave the same but for the point
   * @param stepsMatched the decisions after which the driven battle's state equals the replay
   *     battle's ({@link BattleState})
   * @param reproduced whether every row, command, item and state matched exactly
   * @param stateReproduced whether every decision was driven with every command the same but
   *     perhaps for its point, every item and every state the same: the replay's battle, reproduced
   *     from the labels alone
   * @param firstDivergentTick the tick of the first difference of any kind, or -1
   * @param divergence what differed first, or null
   * @param stop what stopped the drive before the labels' end, or null
   */
  public record Drive(
      int decisions,
      int rowsMatched,
      int commands,
      int replayCommands,
      int matchedCommands,
      int pointsDiffer,
      int stepsMatched,
      boolean reproduced,
      boolean stateReproduced,
      int firstDivergentTick,
      String divergence,
      String stop) {}

  /**
   * Drives a battle from a replay's labels alone, as {@link LadderEnv} plays actions: reset on the
   * replay's setup, each decision's two labels given through the player's commands ({@link
   * LadderEnv#step}), and checks it against the replay's own battle at every decision: the rows
   * before it, then the commands given in it, each play's item as it runs and the state after it.
   *
   * @param replay the replay document the labels were taken from
   * @param labels its labels, of this labeller's decision interval
   * @return what the driven battle came to
   */
  public Drive drive(String replay, Labels labels) {
    try {
      return drive(MAPPER.readTree(replay), labels);
    } catch (IOException e) {
      return failedDrive("not JSON: " + e.getMessage());
    }
  }

  private static Drive failedDrive(String why) {
    return new Drive(0, 0, 0, 0, 0, 0, 0, false, false, -1, why, why);
  }

  /** As {@link #drive(String, Labels)}, on a replay document. */
  public Drive drive(JsonNode replay, Labels labels) {
    if (labels.decisionTicks() != config.decisionTicks()) {
      throw new IllegalArgumentException(
          "labels of "
              + labels.decisionTicks()
              + "-tick decisions, a labeller of "
              + config.decisionTicks());
    }
    ScenarioPlan plan;
    try {
      plan = new ReplayScenario(tables, shape).translate(replay);
    } catch (RuntimeException e) {
      return failedDrive("unreadable: " + e.getMessage());
    }
    Standard1v1Battle truth = ReplayBattle.build(tables, plan);
    LadderEnv env = new LadderEnv(tables, vocabulary, config);
    IntBuffer observations = IntBuffer.allocate(2 * Schema.SEAT_INTS);
    ByteBuffer masks = ByteBuffer.allocate(2 * Schema.ACTIONS);
    env.bind(observations, masks, FloatBuffer.allocate(2), IntBuffer.allocate(Schema.INFO_INTS));
    int decisions = 0;
    int rowsMatched = 0;
    int stepsMatched = 0;
    int matchedCommands = 0;
    int pointsDiffer = 0;
    int compared = 0;
    // The first difference of any kind, and the first that stops the drive.
    String divergence = null;
    int divergentTick = -1;
    String stop = null;
    int[] observation = new int[Schema.SEAT_INTS];
    byte[] mask = new byte[Schema.ACTIONS];
    List<Standard1v1Battle.Command> recorded = new ArrayList<>();
    for (Standard1v1Battle.Command command : truth.getCommands()) {
      if (command.given() < labels.stopTick()) {
        recorded.add(command);
      }
    }
    try {
      env.resetReplay(plan);
      int checked = 0;
      for (int row = 0; row + 1 < labels.rows() && stop == null; row += 2) {
        int tick = env.battle().getBattle().getTick();
        if (tick != labels.ticks()[row] || env.done()) {
          stop =
              "the driven battle is at tick "
                  + tick
                  + (env.done() ? ", its match over" : "")
                  + ", the row's decision at "
                  + labels.ticks()[row];
          if (divergence == null) {
            divergence = stop;
            divergentTick = tick;
          }
          break;
        }
        for (int seat = 0; seat < 2; seat++) {
          observations.get(seat * Schema.SEAT_INTS, observation);
          masks.get(seat * Schema.ACTIONS, mask);
          String differs = rowDiffers(labels, row + seat, observation, mask);
          if (differs == null) {
            rowsMatched++;
          } else if (divergence == null) {
            divergence = "seat " + seat + "'s row at tick " + tick + ": " + differs;
            divergentTick = tick;
          }
        }
        env.step(labels.actions()[row], labels.actions()[row + 1]);
        decisions++;
        int now = env.battle().getBattle().getTick();
        // The commands given in the decision, against the replay's given before the tick now.
        List<Standard1v1Battle.Command> driven = env.battle().getCommands();
        while (stop == null
            && (compared < driven.size()
                || (compared < recorded.size() && recorded.get(compared).given() < now))) {
          boolean replayDue = compared < recorded.size() && recorded.get(compared).given() < now;
          int at;
          if (compared >= driven.size()) {
            stop = "the labels did not give the replay's " + recorded.get(compared);
            at = recorded.get(compared).given();
          } else if (!replayDue) {
            stop = "the labels gave a command the replay does not: " + driven.get(compared);
            at = driven.get(compared).given();
          } else {
            Standard1v1Battle.Command given = driven.get(compared);
            Standard1v1Battle.Command wanted = recorded.get(compared);
            at = wanted.given();
            if (!sameButForPoint(env.battle(), given, wanted)) {
              stop = "a command differs: " + given + " against " + wanted;
            } else if (given.x() != wanted.x() || given.y() != wanted.y()) {
              pointsDiffer++;
              if (divergence == null) {
                divergence = "a command's point differs: " + given + " against " + wanted;
                divergentTick = at;
              }
              compared++;
            } else {
              matchedCommands++;
              compared++;
            }
          }
          if (stop != null && divergence == null) {
            divergence = stop;
            divergentTick = at;
          }
        }
        if (stop != null) {
          break;
        }
        checked = ReplayBattle.checkItems(env.battle(), plan, checked);
        while (truth.getBattle().getTick() < now) {
          truth.getBattle().step();
        }
        if (!Arrays.equals(BattleState.of(env.battle()), BattleState.of(truth))) {
          stop = "the state differs after tick " + now;
          if (divergence == null) {
            divergence = stop;
            divergentTick = now;
          }
          break;
        }
        stepsMatched++;
      }
    } catch (UnsupportedScenarioException e) {
      stop = "an item differs: " + e.feature() + " (" + e.input() + ")";
    } catch (RuntimeException e) {
      stop = "the driven battle failed: " + e;
    }
    if (stop != null && divergence == null) {
      divergence = stop;
      divergentTick = env.battle() == null ? -1 : env.battle().getBattle().getTick();
    }
    int given = env.battle() == null ? 0 : env.battle().getCommands().size();
    boolean allDriven =
        stop == null
            && decisions == labels.rows() / 2
            && compared == recorded.size()
            && given == recorded.size();
    if (stop == null && !allDriven && divergence == null) {
      divergence = "the driven battle gave " + given + " commands, the replay " + recorded.size();
      divergentTick = labels.stopTick();
    }
    boolean reproduced = allDriven && divergence == null && rowsMatched == labels.rows();
    return new Drive(
        decisions,
        rowsMatched,
        given,
        recorded.size(),
        matchedCommands,
        pointsDiffer,
        stepsMatched,
        reproduced,
        allDriven,
        reproduced ? -1 : divergentTick,
        divergence,
        stop);
  }

  /** The first field a row's observation or mask differs in from a labels row, or null. */
  private static String rowDiffers(Labels labels, int row, int[] observation, byte[] mask) {
    int from = row * Schema.SEAT_INTS;
    for (int i = 0; i < Schema.SEAT_INTS; i++) {
      if (labels.observations()[from + i] != observation[i]) {
        String field =
            i < Schema.HEADER_INTS
                ? "header " + i
                : "entity row " + (i - Schema.HEADER_INTS) / Schema.ENTITY_INTS;
        return field + " is " + observation[i] + ", the label's " + labels.observations()[from + i];
      }
    }
    int at = row * Schema.ACTIONS;
    for (int a = 0; a < Schema.ACTIONS; a++) {
      if (labels.masks()[at + a] != mask[a]) {
        return "the mask of action "
            + a
            + " is "
            + mask[a]
            + ", the label's "
            + labels.masks()[at + a];
      }
    }
    return null;
  }

  /** Whether a driven command is the replay's but perhaps for its point. */
  private static boolean sameButForPoint(
      Standard1v1Battle battle,
      Standard1v1Battle.Command driven,
      Standard1v1Battle.Command recorded) {
    if (driven.kind() != recorded.kind()
        || driven.side() != recorded.side()
        || driven.given() != recorded.given()
        || driven.tick() != recorded.tick()) {
      return false;
    }
    if (driven.kind() == Standard1v1Battle.CommandKind.ABILITY) {
      int id = driven.objectId();
      if (id < 0) {
        for (Standard1v1Battle.Play play : battle.getPlays()) {
          for (CharacterEntity unit : play.units()) {
            if (unit.name().equals(driven.unit())) {
              id = unit.getId();
            }
          }
        }
      }
      return id == recorded.objectId();
    }
    return driven.card().equals(recorded.card())
        && driven.level() == recorded.level()
        && driven.option() == recorded.option();
  }

  /** A command of the replay: a play or an ability command, with its scenario index. */
  private record Given(
      int index, int givenTick, int side, ScenarioPlan.Play play, ScenarioPlan.Ability ability) {}

  /** A label worked out for a seat's window, before its row is written. */
  private static final class Pending {
    int action = Schema.WAIT;
    int flags;
    boolean taken;

    /** The scenario index of the command the label says, for the order of the two seats. */
    int index = -1;

    int givenTick = -1;
  }

  /** One replay's labelling: its environment, its commands and the rows written so far. */
  private final class Run {

    private final ScenarioPlan plan;
    private final int endTick;
    private final LadderEnv env;
    private final IntBuffer observations = IntBuffer.allocate(2 * Schema.SEAT_INTS);
    private final ByteBuffer masks = ByteBuffer.allocate(2 * Schema.ACTIONS);

    /** The replay's commands by given tick, each tick's in the scenario's order. */
    private final TreeMap<Integer, List<Given>> byTick = new TreeMap<>();

    private int commandCount;
    private final Rows rows = new Rows();
    private final int[] observation = new int[2 * Schema.SEAT_INTS];
    private final byte[] mask = new byte[2 * Schema.ACTIONS];
    private final Pending[] pending = {new Pending(), new Pending()};

    private int decisions;
    private int labelled;
    private int labelMasked;
    private int collisions;
    private int notInHand;
    private int abilityUnitUnknown;
    private int optionDiffers;
    private int pointUnmatched;
    private int refusedAtGiving;
    private int pointMoved;
    private int notModelledAtGiving;
    private int afterEnd;
    private int orderDiffers;
    private int rowsUnpromised;

    /** Each side's commands queued as the replay states them: given and run tick. */
    private final List<List<int[]>> asRecorded = List.of(new ArrayList<>(), new ArrayList<>());

    Run(ScenarioPlan plan, int endTick) {
      this.plan = plan;
      this.endTick = endTick;
      this.env = new LadderEnv(tables, vocabulary, config);
      env.bind(observations, masks, FloatBuffer.allocate(2), IntBuffer.allocate(Schema.INFO_INTS));
      List<Given> all = new ArrayList<>();
      plan.plays().forEach(p -> all.add(new Given(p.index(), p.givenTick(), p.side(), p, null)));
      plan.abilities()
          .forEach(a -> all.add(new Given(a.index(), a.givenTick(), a.side(), null, a)));
      all.sort(Comparator.comparingInt(Given::index));
      commandCount = all.size();
      for (Given command : all) {
        byTick.computeIfAbsent(command.givenTick(), t -> new ArrayList<>()).add(command);
      }
    }

    Labels label() {
      String refusal = null;
      int stopTick = 0;
      try {
        env.resetReplay(plan);
        int checked = 0;
        while (!env.done() && !atEnd()) {
          int decisionTick = tick();
          observations.get(0, observation);
          masks.get(0, mask);
          for (Pending p : pending) {
            p.action = Schema.WAIT;
            p.flags = 0;
            p.taken = false;
            p.index = -1;
            p.givenTick = -1;
          }
          env.beginDecision();
          for (int t = 0; t < config.decisionTicks() && !env.over() && !atEnd(); t++) {
            List<Given> now = byTick.get(tick());
            if (now != null) {
              for (Given command : now) {
                give(command);
              }
            }
            env.advance();
            checked = ReplayBattle.checkItems(env.battle(), plan, checked);
          }
          env.endDecision();
          if (pending[0].index >= 0 && pending[1].index >= 0) {
            int replayFirst = pending[0].index < pending[1].index ? 0 : 1;
            if (replayFirst != decisions % 2) {
              orderDiffers++;
            }
          }
          for (int seat = 0; seat < 2; seat++) {
            if (unpromised(seat, decisionTick)) {
              pending[seat].flags |= UNPROMISED;
              rowsUnpromised++;
            }
            rows.add(
                observation, mask, seat, pending[seat].action, decisionTick, pending[seat].flags);
          }
          decisions++;
        }
        stopTick = tick();
      } catch (UnsupportedScenarioException e) {
        stopTick = tickOrZero();
        refusal = "unsupported at tick " + stopTick + ": " + e.feature() + " (" + e.input() + ")";
      } catch (CoreFailure e) {
        stopTick = tickOrZero();
        refusal = "the battle core failed at tick " + stopTick + ": " + e.getCause();
      } catch (RuntimeException e) {
        stopTick = tickOrZero();
        refusal = "failed at tick " + stopTick + ": " + e;
      }
      for (Map.Entry<Integer, List<Given>> entry : byTick.tailMap(stopTick, true).entrySet()) {
        afterEnd += entry.getValue().size();
      }
      int notLabelledInexpressible = notInHand + abilityUnitUnknown + optionDiffers;
      Counts counts =
          new Counts(
              decisions,
              rows.size,
              commandCount,
              plan.plays().size(),
              plan.abilities().size(),
              labelled,
              labelMasked,
              collisions,
              notLabelledInexpressible,
              notInHand,
              abilityUnitUnknown,
              optionDiffers,
              pointUnmatched,
              refusedAtGiving + pointMoved + notModelledAtGiving,
              refusedAtGiving,
              pointMoved,
              notModelledAtGiving,
              afterEnd,
              orderDiffers,
              rowsUnpromised);
      return rows.labels(config.decisionTicks(), counts, stopTick, refusal);
    }

    private int tick() {
      return env.battle().getBattle().getTick();
    }

    /** Whether a command of a seat queued as recorded waits at a decision's observation. */
    private boolean unpromised(int seat, int tick) {
      for (int[] command : asRecorded.get(seat)) {
        if (command[0] < tick && tick <= command[1]) {
          return true;
        }
      }
      return false;
    }

    private int tickOrZero() {
      return env.battle() == null ? 0 : tick();
    }

    /** Whether the replay's last tick is reached. */
    private boolean atEnd() {
      return endTick >= 0 && tick() >= endTick;
    }

    /** Labels a command, when it is its seat's first of the window, and gives it to the battle. */
    private void give(Given command) {
      int side = command.side();
      Pending label = pending[side];
      boolean first = !label.taken;
      if (first) {
        label.taken = true;
        label.index = command.index();
        label.givenTick = command.givenTick();
      } else {
        collisions++;
        label.flags |= COLLISION;
      }
      if (command.play() != null) {
        givePlay(command.play(), label, first);
      } else {
        giveAbility(command.ability(), label, first);
      }
    }

    private void givePlay(ScenarioPlan.Play play, Pending label, boolean first) {
      int side = play.side();
      Standard1v1Battle battle = env.battle();
      LadderMatch match = battle.getMatch();
      int deckIndex = plan.decks().get(side).indexOf(play.card());
      MatchCard card = match.side(side).deck().get(deckIndex);
      boolean optionKnown =
          card.variant() == null
              || match.pickOption(side, card.name(), env.commands().promised(side))
                  == play.option().index();
      DeployCard searched = searchCard(battle, side, deckIndex, play);
      if (first) {
        int slot = slotAt(observation, side, vocabulary.id(play.card()));
        if (slot < 0) {
          notInHand++;
          label.flags |= INEXPRESSIBLE;
        } else if (!optionKnown) {
          optionDiffers++;
          label.flags |= INEXPRESSIBLE;
        } else {
          int[] tile =
              tile(
                  battle,
                  searched,
                  side,
                  play.x(),
                  play.y(),
                  config.maskVersion() == Schema.MASK_LANDING_TILES);
          if (tile[2] == 0) {
            pointUnmatched++;
            label.flags |= POINT_UNMATCHED;
          }
          setLabel(label, side, Schema.play(slot, tile[0], tile[1]));
        }
      }
      String name = "cmd" + play.index();
      int slotNow = handSlot(match, side, deckIndex);
      int[] sent =
          searched == null ? null : battle.searchPlacement(searched, side, play.x(), play.y());
      if (slotNow < 0 || !optionKnown) {
        refusedAtGiving++;
      } else if (sent == null || sent[0] != play.x() || sent[1] != play.y()) {
        pointMoved++;
      } else {
        PlayerCommands.Giving giving;
        try {
          giving = env.givePlay(side, slotNow, play.x(), play.y(), name);
        } catch (UnsupportedOperationException e) {
          notModelledAtGiving++;
          giving = null;
        }
        if (giving != null && giving.given()) {
          if (giving.givenTick() != play.givenTick()
              || giving.runTick() != play.runTick()
              || giving.x() != play.x()
              || giving.y() != play.y()) {
            throw new IllegalStateException(
                name + " was given as " + giving + ", the replay records " + play);
          }
          return;
        }
        if (giving != null) {
          refusedAtGiving++;
        }
      }
      label.flags |= GIVEN_AS_RECORDED;
      asRecorded.get(side).add(new int[] {play.givenTick(), play.runTick()});
      queueAsRecorded(battle, play, name);
    }

    private void giveAbility(ScenarioPlan.Ability ability, Pending label, boolean first) {
      int side = ability.side();
      int slot = -1;
      CharacterEntity unit = null;
      for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
        CharacterEntity candidate = env.abilityEntity(side, k);
        if (candidate != null && candidate.getId() == ability.objectId()) {
          slot = k;
          unit = candidate;
          break;
        }
      }
      if (first) {
        if (slot < 0) {
          abilityUnitUnknown++;
          label.flags |= INEXPRESSIBLE;
        } else {
          setLabel(label, side, Schema.ability(slot));
        }
      }
      String name = "cmd" + ability.index();
      if (unit != null) {
        PlayerCommands.Giving giving;
        try {
          giving = env.giveAbility(side, slot, unit.name(), name);
        } catch (UnsupportedOperationException e) {
          notModelledAtGiving++;
          giving = null;
        }
        if (giving != null && giving.given()) {
          if (giving.givenTick() != ability.givenTick() || giving.runTick() != ability.runTick()) {
            throw new IllegalStateException(
                name + " was given as " + giving + ", the replay records " + ability);
          }
          return;
        }
        if (giving != null) {
          refusedAtGiving++;
        }
      } else {
        refusedAtGiving++;
      }
      label.flags |= GIVEN_AS_RECORDED;
      asRecorded.get(side).add(new int[] {ability.givenTick(), ability.runTick()});
      Standard1v1Battle battle = env.battle();
      battle.useAbility(ability.givenTick(), ability.runTick(), side, ability.objectId(), name);
      if (slot >= 0) {
        env.trackAbility(name, side, slot, ability.runTick());
      }
    }

    private void setLabel(Pending label, int side, int action) {
      label.action = action;
      labelled++;
      if (mask[side * Schema.ACTIONS + action] == 0) {
        labelMasked++;
        label.flags |= LABEL_MASKED;
      }
    }

    /** Queues a recorded play as {@link ReplayBattle#build} does, and keeps it as waiting. */
    private void queueAsRecorded(Standard1v1Battle battle, ScenarioPlan.Play play, String name) {
      if (play.repeats() != null) {
        battle.playMirror(
            play.givenTick(),
            play.runTick(),
            play.card(),
            play.level(),
            play.side(),
            play.x(),
            play.y(),
            name);
      } else if (play.option() != null) {
        battle.playVariant(
            play.givenTick(),
            play.runTick(),
            play.card(),
            play.option().index(),
            play.level(),
            play.side(),
            play.x(),
            play.y(),
            name);
      } else {
        battle.play(
            play.givenTick(),
            play.runTick(),
            battle.getWorld().getRecords().card(play.card()),
            play.level(),
            play.side(),
            play.x(),
            play.y(),
            name);
      }
      env.trackPlay(name, play.side(), play.card(), play.x(), play.y(), play.runTick());
    }
  }

  /** The hand slot a deck card holds now, or -1. */
  private static int handSlot(LadderMatch match, int side, int deckIndex) {
    int[] slots = match.side(side).getHand().slots();
    for (int k = 0; k < slots.length; k++) {
      if (slots[k] != Hand.EMPTY && slots[k] == deckIndex) {
        return k;
      }
    }
    return -1;
  }

  /** The hand slot an observation of both seats shows a card in for a seat, or -1. */
  private static int slotAt(int[] observation, int seat, int card) {
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      if (observation[seat * Schema.SEAT_INTS + Schema.H_HAND_CARD + k] == card) {
        return k;
      }
    }
    return -1;
  }

  /**
   * The card the screen's placement search places for a play, as {@link PlayerCommands} hands it
   * over: a Mirror's as its king's last card (null for none), a variant card's as the option the
   * replay names, an evolved or hero play's as its row in that form, any other as the card.
   */
  private static DeployCard searchCard(
      Standard1v1Battle battle, int side, int deckIndex, ScenarioPlan.Play play) {
    LadderMatch match = battle.getMatch();
    BattleRecords records = battle.getWorld().getRecords();
    MatchCard card = match.side(side).deck().get(deckIndex);
    if (card.mirror()) {
      MatchCard last = match.mirrorItem(side, deckIndex, play.level()).repeats();
      return last == null ? null : records.card(last.name());
    }
    if (card.variant() != null) {
      return records.card(match.variantItem(side, deckIndex, play.option().index()).spell());
    }
    EvolutionItem item = match.item(side, deckIndex);
    return item.field() != 0 ? records.card(item.spell().name()) : records.card(card.name());
  }

  /**
   * The tile of a seat's frame a recorded point is labelled with: {@code {col, row, 1}} for the
   * first tile, of those touching the point (the one holding it first), whose centre the placement
   * search sends to the point now; {@code {col, row, 0}} for the tile holding it when none does.
   */
  static int[] tile(Standard1v1Battle battle, DeployCard card, int side, int x, int y) {
    return tile(battle, card, side, x, y, false);
  }

  /**
   * The tile of a seat's frame a recorded point is labelled with, as {@link
   * #tile(Standard1v1Battle, DeployCard, int, int, int)}, but under the landing mask rule the tile
   * the play landed on comes first: the tile of the game's grid holding the point, when the search
   * from its centre sends to the point, as it does when the card lands there unmoved. That is the
   * one tile the landing mask opens for the point; another touching tile whose centre the search
   * moves onto the point is closed in it. A recorded point is where the screen's search put the
   * tap, so the tap the player made, perhaps elsewhere, is labelled with the tile it landed on.
   *
   * @param landing whether the landing tile comes first
   */
  static int[] tile(
      Standard1v1Battle battle, DeployCard card, int side, int x, int y, boolean landing) {
    int gameCol = Math.floorDiv(x, Schema.TILE_UNITS);
    int gameRow = Math.floorDiv(y, Schema.TILE_UNITS);
    if (landing
        && card != null
        && gameCol >= 0
        && gameCol < Schema.COLS
        && gameRow >= 0
        && gameRow < Schema.ROWS) {
      int col = Schema.toSeatX(side, gameCol * Schema.TILE_UNITS + Schema.TILE_UNITS / 2);
      int row = Schema.toSeatY(side, gameRow * Schema.TILE_UNITS + Schema.TILE_UNITS / 2);
      col /= Schema.TILE_UNITS;
      row /= Schema.TILE_UNITS;
      int[] sent =
          battle.searchPlacement(card, side, Schema.gameX(side, col), Schema.gameY(side, row));
      if (sent != null && sent[0] == x && sent[1] == y) {
        return new int[] {col, row, 1};
      }
    }
    int sx = Schema.toSeatX(side, x);
    int sy = Schema.toSeatY(side, y);
    int[] cols = around(sx, Schema.COLS);
    int[] rows = around(sy, Schema.ROWS);
    if (card != null) {
      for (int row : rows) {
        for (int col : cols) {
          int[] sent =
              battle.searchPlacement(card, side, Schema.gameX(side, col), Schema.gameY(side, row));
          if (sent != null && sent[0] == x && sent[1] == y) {
            return new int[] {col, row, 1};
          }
        }
      }
    }
    return new int[] {cols[0], rows[0], 0};
  }

  /**
   * The tiles along one axis touching a coordinate of the seat's frame: the one holding it first,
   * and the one before when it lies on their border; clamped to the grid.
   */
  private static int[] around(int at, int count) {
    int holding = Math.min(Math.max(Math.floorDiv(at, Schema.TILE_UNITS), 0), count - 1);
    int before = Math.floorDiv(at, Schema.TILE_UNITS) - 1;
    if (Math.floorMod(at, Schema.TILE_UNITS) == 0
        && before >= 0
        && before < count
        && before != holding) {
      return new int[] {holding, before};
    }
    return new int[] {holding};
  }

  /** The rows of a replay, grown as they are written. */
  private static final class Rows {
    private int size;
    private int[] observations = new int[0];
    private byte[] masks = new byte[0];
    private int[] actions = new int[0];
    private int[] ticks = new int[0];
    private int[] seats = new int[0];
    private int[] flags = new int[0];

    void add(int[] observation, byte[] mask, int seat, int action, int tick, int flag) {
      if (size == actions.length) {
        int grown = Math.max(64, size * 2);
        observations = Arrays.copyOf(observations, grown * Schema.SEAT_INTS);
        masks = Arrays.copyOf(masks, grown * Schema.ACTIONS);
        actions = Arrays.copyOf(actions, grown);
        ticks = Arrays.copyOf(ticks, grown);
        seats = Arrays.copyOf(seats, grown);
        flags = Arrays.copyOf(flags, grown);
      }
      System.arraycopy(
          observation,
          seat * Schema.SEAT_INTS,
          observations,
          size * Schema.SEAT_INTS,
          Schema.SEAT_INTS);
      System.arraycopy(mask, seat * Schema.ACTIONS, masks, size * Schema.ACTIONS, Schema.ACTIONS);
      actions[size] = action;
      ticks[size] = tick;
      seats[size] = seat;
      flags[size] = flag;
      size++;
    }

    Labels labels(int decisionTicks, Counts counts, int stopTick, String refusal) {
      return new Labels(
          decisionTicks,
          size,
          Arrays.copyOf(observations, size * Schema.SEAT_INTS),
          Arrays.copyOf(masks, size * Schema.ACTIONS),
          Arrays.copyOf(actions, size),
          Arrays.copyOf(ticks, size),
          Arrays.copyOf(seats, size),
          Arrays.copyOf(flags, size),
          counts,
          stopTick,
          refusal);
    }
  }
}
