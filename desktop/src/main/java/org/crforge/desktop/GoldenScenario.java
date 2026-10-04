package org.crforge.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.util.GameUnits;

/**
 * One reference deployment the visualizer can replay and compare against.
 *
 * <p>Three cases are bundled with the module: a Knight deployed on the left, on the right and in
 * the centre of the blue half of the standard arena. Each case is a reference trajectory - one
 * position, state, target and route length per 50 ms tick - produced by a model of the game's
 * movement and targeting rules, not a capture of the shipped game. The visualizer places the same
 * unit at the same place on the battle core, as its own golden trajectory test does (see {@code
 * BattleSession#scenario}), draws the reference trajectory as a ghost, and remembers the first tick
 * on which the live unit is anywhere other than where the reference says it should be.
 *
 * <p>Tick alignment: the unit is placed by a command due on the battle's tick 0, which runs at the
 * head of the first step, and the step's opening cleanup admits it, so it is first visited in that
 * step. The battle as the first step leaves it, its tick count 1, is the reference's tick 0. {@link
 * #referenceTick(int)} is that conversion and nothing else.
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

  /** The battle's tick count at the moment the unit was placed: the tick its command is due on. */
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
   * @param battleTick the battle's tick count at the moment the unit was placed, the tick its
   *     placement command is due on, which is the step the reference's tick 0 is read after
   */
  public void begin(Case replayedCase, int battleTick) {
    this.activeCase = replayedCase;
    this.spawnFrame = battleTick;
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
   * The reference tick the battle's tick count corresponds to. Negative before the unit exists.
   *
   * @param battleTick the battle's tick count, read after the step has run
   */
  public int referenceTick(int battleTick) {
    return battleTick - spawnFrame - 1;
  }

  /**
   * Compares one tick of the live unit with the reference and remembers the first tick that differs
   * by anything at all.
   *
   * <p>Called once per battle step from inside the visualizer's step loop, so that a frame covering
   * several steps compares every one of them.
   *
   * @param battleTick the battle's tick count after the step that just ran
   * @param liveX the live unit's position along the arena's width in game units
   * @param liveY the live unit's position along the arena's length in game units
   */
  public void sample(int battleTick, int liveX, int liveY) {
    if (activeCase == null || firstDeviationTick != null) {
      return;
    }
    int tick = referenceTick(battleTick);
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
}
