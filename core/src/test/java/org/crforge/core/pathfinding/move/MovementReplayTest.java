package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateSetter;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
import org.junit.jupiter.api.Test;

/**
 * Drives a whole Knight deployment tick by tick and checks every position against a recorded
 * trajectory.
 *
 * <p>The recorded trajectories are three deployments of a Knight on the standard arena with nothing
 * else on it but the six crown towers. Under those conditions nothing pushes the unit and nothing
 * steers it around a neighbour, so the whole path is the route follower, the displacement and the
 * state visit.
 *
 * <p>Two pieces of each tick are fed in rather than computed, because they belong to parts of the
 * simulation this package does not cover:
 *
 * <ul>
 *   <li>the tower the unit is heading for, and the tick it locks onto it, both taken from the
 *       recording, which is what target selection would produce;
 *   <li>the route searches, endpoint scans, relocations and cell tests, replayed from a recorded
 *       log that also asserts the inputs each of them is asked with, which is what the routing grid
 *       would produce.
 * </ul>
 *
 * <p>Everything else - the movement visit, route preparation, the follower, the waypoint selector,
 * the push pass, the displacement, the speed budget, the gates, the direction initializer, the
 * route-beyond-reference predicate and the entity state visit - runs for real, and the unit's
 * position, state and remaining route length are compared on every tick.
 */
class MovementReplayTest {

  private static final int WIDTH = 36;

  /** The Knight's speed column, in game units per tick. */
  private static final int KNIGHT_SPEED = 60;

  /** The Knight's collision radius, in game units. */
  private static final int KNIGHT_RADIUS = 500;

  /** The Knight's mass. */
  private static final int KNIGHT_MASS = 6;

  /** The Knight's deploy time, in milliseconds. */
  private static final int KNIGHT_DEPLOY_MS = 1000;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Where each crown tower stands, in game units, with side 1's towers mirrored down the arena. */
  private static final Map<String, int[]> TOWERS =
      Map.of(
          "KingTower_0_0", new int[] {9000, 3000},
          "PrincessTower_0_1", new int[] {3500, 6500},
          "PrincessTower_0_2", new int[] {14500, 6500},
          "KingTower_1_0", new int[] {9000, 29000},
          "PrincessTower_1_1", new int[] {3500, 25500},
          "PrincessTower_1_2", new int[] {14500, 25500});

  @Test
  void aKnightDeployedOnTheLeftWalksTheLeftLaneAndLocksOntoTheLeftTower() {
    replay("knight_left");
  }

  @Test
  void aKnightDeployedOnTheRightWalksTheRightLaneAndLocksOntoTheRightTower() {
    replay("knight_right");
  }

  @Test
  void aKnightDeployedInTheCentreSwitchesTowerAndStillArrives() {
    replay("knight_centre");
  }

  private void replay(String caseName) {
    JsonNode golden = load("/pathfinding/golden/" + caseName + ".json");
    JsonNode log = load("/pathfinding/movement_replay/" + caseName + ".json");
    Map<Integer, Deque<JsonNode>> recordedCalls = new HashMap<>();
    for (JsonNode call : log.get("calls")) {
      recordedCalls
          .computeIfAbsent(call.get("tick").asInt(), tick -> new ArrayDeque<>())
          .addLast(call);
    }

    int side = golden.get("side").asInt();
    int deployX = golden.get("deploy").get(0).asInt();
    int deployY = golden.get("deploy").get(1).asInt();

    CellGrid grid =
        new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);
    MovementConfig config = MovementConfig.forGroundUnit();
    MovementGlobals globals = MovementGlobals.forStandardArena(WIDTH);
    SpeedConfig speedConfig = SpeedConfig.forGroundUnit(KNIGHT_SPEED);
    StateVisitConfig stateConfig = StateVisitConfig.forGroundUnit(KNIGHT_DEPLOY_MS);

    GridEntity unit = new GridEntity();
    unit.setName("owner");
    unit.setSide(side);
    unit.setX(deployX);
    unit.setY(deployY);
    unit.setDirX(0);
    unit.setDirY(side == 0 ? 256 : -256);
    unit.setCollisionRadius(KNIGHT_RADIUS);
    unit.setMass(KNIGHT_MASS);
    unit.setMovementActive(true);
    unit.setState(GridEntityState.DEPLOYING);
    unit.setDeployCountdown(KNIGHT_DEPLOY_MS);

    MovementState component = MovementState.forSide(side, deployX, deployY);
    StateTimers timers = new StateTimers();
    ReplayQueries queries =
        new ReplayQueries(unit, component, config, speedConfig, recordedCalls, globals);

    for (JsonNode record : golden.get("records")) {
      int tick = record.get("tick").asInt();
      queries.beginTick(tick);
      ReferencePoint reference =
          record.get("ref").isNull() ? null : point(record.get("ref").asText());
      queries.reference = reference;

      if (unit.getState() == GridEntityState.DEPLOYING) {
        assertRecord(caseName, record, unit, component, tick);
        stateVisit(unit, timers, component, stateConfig);
        queries.assertTickDrained(caseName, tick);
        continue;
      }

      // The targeting pass is not part of this package: the recorded lock is applied here.
      if (record.get("state").asInt() == GridEntityState.ATTACKING) {
        unit.setState(GridEntityState.ATTACKING);
      }

      MovementChain chain =
          new MovementChain(component, unit, grid, config, globals, reference, List.of(), queries);
      MovementVisit.movementVisit(component, unit, null, config, null, queries, false, chain);
      stateVisit(unit, timers, component, stateConfig);

      assertRecord(caseName, record, unit, component, tick);
      queries.assertTickDrained(caseName, tick);
    }
  }

  private static void stateVisit(
      GridEntity unit, StateTimers timers, MovementState component, StateVisitConfig config) {
    EntityStateVisit.stateVisit(
        unit,
        timers,
        component,
        config,
        StateVisitGlobals.standard(),
        StateQueries.forUnitWithRoute(unit.getSide() & 1),
        new ArrayList<>(),
        StateSetter.guarded());
  }

  private static void assertRecord(
      String caseName, JsonNode record, GridEntity unit, MovementState component, int tick) {
    String where = caseName + " tick " + tick;
    assertThat(unit.getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
    assertThat(unit.getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
    assertThat(unit.getState()).as("%s state", where).isEqualTo(record.get("state").asInt());
    assertThat(component.getRoute().size())
        .as("%s route length", where)
        .isEqualTo(record.get("route").asInt());
  }

  private static ReferencePoint point(String tower) {
    int[] position = TOWERS.get(tower);
    if (position == null) {
      throw new IllegalArgumentException("Unknown tower in the recording: " + tower);
    }
    return new ReferencePoint(position[0], position[1]);
  }

  private static JsonNode load(String resource) {
    try (InputStream stream = MovementReplayTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }

  /**
   * Answers the movement pass the way the recording did: the grid answers come from the log and are
   * checked against the inputs they were recorded with, while the speed budget, the gates and the
   * route-beyond-reference predicate are computed for real.
   */
  private static final class ReplayQueries implements MovementQueries {

    private final GridEntity unit;
    private final MovementState component;
    private final MovementConfig config;
    private final SpeedConfig speedConfig;
    private final Map<Integer, Deque<JsonNode>> recordedCalls;
    private final MovementGlobals globals;

    private ReferencePoint reference;
    private Deque<JsonNode> pending = new ArrayDeque<>();
    private int tick;

    ReplayQueries(
        GridEntity unit,
        MovementState component,
        MovementConfig config,
        SpeedConfig speedConfig,
        Map<Integer, Deque<JsonNode>> recordedCalls,
        MovementGlobals globals) {
      this.unit = unit;
      this.component = component;
      this.config = config;
      this.speedConfig = speedConfig;
      this.recordedCalls = recordedCalls;
      this.globals = globals;
    }

    void beginTick(int tick) {
      this.tick = tick;
      this.pending = recordedCalls.getOrDefault(tick, new ArrayDeque<>());
    }

    void assertTickDrained(String caseName, int tick) {
      if (!pending.isEmpty()) {
        fail(
            "%s tick %d: %d recorded grid answers were never asked for, next is %s",
            caseName, tick, pending.size(), pending.peekFirst().get("query").asText());
      }
    }

    private JsonNode next(String query, int... inputs) {
      JsonNode call = pending.pollFirst();
      assertThat(call).as("tick %d: no recorded answer left for %s", tick, query).isNotNull();
      assertThat(call.get("query").asText()).as("tick %d: query order", tick).isEqualTo(query);
      int[] recorded = new int[call.get("inputs").size()];
      for (int i = 0; i < recorded.length; i++) {
        recorded[i] = call.get("inputs").get(i).asInt();
      }
      assertThat(inputs).as("tick %d: inputs of %s", tick, query).containsExactly(recorded);
      return call;
    }

    @Override
    public Route search(int startCol, int startRow, int goalCol, int goalRow, int adjust) {
      JsonNode call = next("search", startCol, startRow, goalCol, goalRow, adjust);
      Route route = new Route();
      for (JsonNode node : call.get("outputs")) {
        route.add(node.asInt());
      }
      return route;
    }

    @Override
    public int endpoint(int referenceCol, int referenceRow, int radius) {
      return next("endpoint", referenceCol, referenceRow, radius).get("output").asInt();
    }

    @Override
    public int attackRange() {
      return next("attackRange").get("output").asInt();
    }

    @Override
    public int farther() {
      int answer =
          RouteBeyondReference.routeBeyondReference(component, unit, reference, globals.width());
      assertThat(answer)
          .as("tick %d: route beyond reference", tick)
          .isEqualTo(next("farther").get("output").asInt());
      return answer;
    }

    @Override
    public int relocate(int x, int y) {
      return next("relocate", x, y).get("output").asInt();
    }

    @Override
    public int cellTest(int worldX, int worldY) {
      return next("cellTest", worldX, worldY).get("output").asInt();
    }

    private SpeedInputs speedInputs() {
      return new SpeedInputs(
          unit.getFlags(),
          unit.getState(),
          true,
          0,
          0,
          0,
          unit.getBlockCountdownMs(),
          new int[0],
          true,
          component.getChargeProgress());
    }

    @Override
    public int speedBudget() {
      return SpeedBudget.speedBudget(speedInputs(), speedConfig, SpeedGlobals.standard());
    }

    @Override
    public int facingGate() {
      return MovementGates.facingGate(speedInputs());
    }

    @Override
    public int avoidanceGate() {
      return MovementGates.avoidanceGate(speedInputs());
    }

    @Override
    public int pushGate() {
      return MovementGates.pushGate(speedInputs(), speedConfig);
    }

    @Override
    public int routeRequest() {
      int state = unit.getState();
      return state == GridEntityState.MOVING
              || state == GridEntityState.SPAWN_PATHFIND
              || state == GridEntityState.INGAME_PATHFIND
          ? 1
          : 0;
    }

    @Override
    public int ownerSide() {
      return unit.getSide();
    }

    @Override
    public int air() {
      return unit.isAir() ? 1 : 0;
    }

    @Override
    public int ground() {
      return unit.isAir() ? 0 : 1;
    }

    @Override
    public int referenceAvailable() {
      return reference == null ? 0 : 1;
    }

    @Override
    public int gridWidth() {
      return globals.width();
    }

    @Override
    public GridMoveEntity entityView() {
      return GridMoveEntity.of(unit, config);
    }
  }
}
