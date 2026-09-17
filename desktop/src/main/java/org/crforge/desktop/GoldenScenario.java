package org.crforge.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.Getter;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.GameUnits;
import org.crforge.data.card.CardRegistry;

/**
 * One reference deployment the visualizer can replay and compare against.
 *
 * <p>Three cases are bundled with the module: a Knight deployed on the left, on the right and in
 * the centre of the blue half of the standard arena. Each case is a reference trajectory - one
 * position, state, target and route length per 50 ms tick - produced by a model of the game's
 * movement and targeting rules, not a capture of the shipped game. The visualizer deploys the same
 * unit at the same place through the same public spawn path the grid pathfinding tests use, draws
 * the reference trajectory as a ghost, and remembers the first tick on which the live unit is
 * anywhere other than where the reference says it should be.
 *
 * <p>Tick alignment: a spawn is queued and flushed at the head of the following tick, so the first
 * engine tick after the spawn is the first tick the unit exists in, which is the reference's tick
 * 0. {@link #referenceTick(int)} is that conversion and nothing else.
 *
 * <p>This class does no drawing and holds no graphics resources; {@link
 * org.crforge.desktop.render.GoldenTrajectoryRenderer} draws what it exposes.
 */
public final class GoldenScenario {

  /** The bundled cases, in the order the scenario key cycles through them. */
  public static final List<String> CASE_NAMES =
      List.of("knight_left", "knight_right", "knight_centre");

  private static final String RESOURCE_PREFIX = "/trajectories/";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The level the reference trajectories scale both the towers and the unit to. */
  public static final int LEVEL = 11;

  /**
   * One tick of a reference trajectory.
   *
   * @param tick the tick, counted from the first tick the unit exists in
   * @param x position along the arena's width in game units
   * @param y position along the arena's length in game units
   * @param state the entity state the unit was in
   * @param reference the name of the tower the unit was heading for, or null when it had none
   * @param routeNodes how many cells were left on its route
   */
  public record Entry(int tick, int x, int y, int state, String reference, int routeNodes) {}

  /**
   * One whole reference deployment.
   *
   * @param name the case name, which is also the resource it was loaded from
   * @param card the card that was deployed
   * @param deployX the deploy position along the arena's width in game units
   * @param deployY the deploy position along the arena's length in game units
   * @param side 0 for the blue player, 1 for the red one
   * @param lane the road the deploy position sits nearest to
   * @param records one entry per tick, in tick order
   */
  public record Case(
      String name,
      String card,
      int deployX,
      int deployY,
      int side,
      int lane,
      List<Entry> records) {}

  /** The case currently being replayed, or null when no scenario has been started. */
  @Getter private Case activeCase;

  /** Index into {@link #CASE_NAMES} of the case the next press selects. */
  private int nextIndex;

  /** The engine's tick count at the moment the unit was spawned. */
  private int spawnFrame;

  /** The reference tick of the first sample that did not match, or null while none has. */
  private Integer firstDeviationTick;

  /** How far the live unit was from the reference on that tick, in game units. */
  private int firstDeviationDistance;

  /** The reference position of that tick, which is what the overlay highlights. */
  private int[] deviationPoint;

  /** The name of the case the next scenario press selects, advancing the cycle by one. */
  public String nextCaseName() {
    String name = CASE_NAMES.get(nextIndex);
    nextIndex = (nextIndex + 1) % CASE_NAMES.size();
    return name;
  }

  /**
   * Starts replaying a case.
   *
   * @param replayedCase the case to replay
   * @param engineFrameCount the engine's tick count at the moment the unit was spawned, which is
   *     the tick the reference's tick 0 follows
   */
  public void begin(Case replayedCase, int engineFrameCount) {
    this.activeCase = replayedCase;
    this.spawnFrame = engineFrameCount;
    this.firstDeviationTick = null;
    this.firstDeviationDistance = 0;
    this.deviationPoint = null;
  }

  /** Forgets the case and every comparison made against it. */
  public void clear() {
    this.activeCase = null;
    this.spawnFrame = 0;
    this.firstDeviationTick = null;
    this.firstDeviationDistance = 0;
    this.deviationPoint = null;
  }

  /** True while a case is being replayed. */
  public boolean isActive() {
    return activeCase != null;
  }

  /**
   * The reference tick the engine's tick count corresponds to. Negative before the unit exists.
   *
   * @param engineFrameCount the engine's tick count, read after the tick has run
   */
  public int referenceTick(int engineFrameCount) {
    return engineFrameCount - spawnFrame - 1;
  }

  /**
   * Compares one tick of the live unit with the reference and remembers the first tick that differs
   * by anything at all.
   *
   * <p>Called once per engine tick from inside the visualizer's tick loop, so that a frame covering
   * several ticks compares every one of them.
   *
   * @param engineFrameCount the engine's tick count after the tick that just ran
   * @param liveX the live unit's position along the arena's width in game units
   * @param liveY the live unit's position along the arena's length in game units
   */
  public void sample(int engineFrameCount, int liveX, int liveY) {
    if (activeCase == null || firstDeviationTick != null) {
      return;
    }
    int tick = referenceTick(engineFrameCount);
    int[] golden = goldenAt(tick);
    if (golden == null) {
      return;
    }
    if (golden[0] == liveX && golden[1] == liveY) {
      return;
    }
    firstDeviationTick = tick;
    firstDeviationDistance =
        (int) Math.round(GameUnits.distance(golden[0], golden[1], liveX, liveY));
    deviationPoint = new int[] {golden[0], golden[1]};
  }

  /**
   * The reference tick of the first mismatch, or null while the live unit has matched every tick.
   */
  public Integer firstDeviationTick() {
    return firstDeviationTick;
  }

  /** How far apart the two positions were on that tick, in game units; 0 while there is none. */
  public int firstDeviationDistance() {
    return firstDeviationDistance;
  }

  /** The reference position of the first mismatch, or null while there has been none. */
  public int[] deviationPoint() {
    return deviationPoint == null ? null : deviationPoint.clone();
  }

  /**
   * The reference position of one tick, or null when the trajectory does not reach that tick.
   *
   * @param tick a reference tick, as {@link #referenceTick(int)} produces
   */
  public int[] goldenAt(int tick) {
    if (activeCase == null || tick < 0 || tick >= activeCase.records().size()) {
      return null;
    }
    Entry entry = activeCase.records().get(tick);
    return new int[] {entry.x(), entry.y()};
  }

  /**
   * Every reference position, one per tick, in tick order; empty when no case is being replayed.
   */
  public List<int[]> goldenPath() {
    if (activeCase == null) {
      return List.of();
    }
    List<int[]> path = new ArrayList<>(activeCase.records().size());
    for (Entry entry : activeCase.records()) {
      path.add(new int[] {entry.x(), entry.y()});
    }
    return path;
  }

  /** The two lines the status column shows while a scenario is being replayed. */
  public List<String> statusLines() {
    if (activeCase == null) {
      return List.of();
    }
    String deviation =
        firstDeviationTick == null
            ? "deviation: none"
            : "first deviation at tick "
                + firstDeviationTick
                + ", distance "
                + firstDeviationDistance
                + " units";
    return List.of("scenario: " + activeCase.name(), deviation);
  }

  /**
   * Deploys the case's unit at the case's position, through the public spawn path.
   *
   * <p>The unit is spawned rather than played from a hand: that skips the placement sync delay and
   * the elixir cost, so its first countdown tick is the engine's next tick, which is what the tick
   * alignment above assumes. The card's own deploy time is passed so the simulator's deploy timer
   * and the grid deploy countdown run for the same twenty ticks.
   *
   * @param engine the engine to deploy into, already reset and set up for the run
   * @param deployedCase the case whose card, position and side are deployed
   * @return the engine's tick count at the moment of the spawn, to pass to {@link #begin}
   */
  public int deploy(GameEngine engine, Case deployedCase) {
    Card card = CardRegistry.get(cardId(deployedCase.card()));
    Objects.requireNonNull(card, () -> "Unknown card " + deployedCase.card());
    engine
        .getSpawnerSystem()
        .spawnUnit(
            deployedCase.deployX(),
            deployedCase.deployY(),
            deployedCase.side() == 0 ? Team.BLUE : Team.RED,
            card.getUnitStats(),
            LEVEL,
            card.getUnitStats().getDeployTime());
    return engine.getGameState().getFrameCount();
  }

  /**
   * The live troop of the active case: the first alive troop of the case's side carrying the case's
   * card name. Null when no case is active or the unit has died.
   */
  public Troop findUnit(GameEngine engine) {
    if (activeCase == null) {
      return null;
    }
    Team team = activeCase.side() == 0 ? Team.BLUE : Team.RED;
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      if (entity instanceof Troop troop
          && troop.getTeam() == team
          && activeCase.card().equals(troop.getName())) {
        return troop;
      }
    }
    return null;
  }

  /** Reads one bundled case. */
  public static Case load(String caseName) {
    String resource = RESOURCE_PREFIX + caseName + ".json";
    try (InputStream stream = GoldenScenario.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalArgumentException("Missing trajectory resource " + resource);
      }
      JsonNode root = MAPPER.readTree(stream);
      List<Entry> records = new ArrayList<>();
      for (JsonNode node : root.get("records")) {
        JsonNode reference = node.get("ref");
        records.add(
            new Entry(
                node.get("tick").asInt(),
                node.get("x").asInt(),
                node.get("y").asInt(),
                node.get("state").asInt(),
                reference == null || reference.isNull() ? null : reference.asText(),
                node.get("route").asInt()));
      }
      return new Case(
          caseName,
          root.get("card").asText(),
          root.get("deploy").get(0).asInt(),
          root.get("deploy").get(1).asInt(),
          root.get("side").asInt(),
          root.get("lane").asInt(),
          List.copyOf(records));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }

  /** The card registry's id of a card name: the name lower-cased with its spaces removed. */
  private static String cardId(String cardName) {
    return cardName.toLowerCase(Locale.ROOT).replace(" ", "");
  }
}
