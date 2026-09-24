package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Replays the reference trajectories through the targeting pass.
 *
 * <p>The trajectories hold one line per tick with the unit's position, its state and the target it
 * held. Movement belongs to another pass, so the unit is put at the position of the previous tick
 * and only the targeting pass is run; the target it chooses and the state it ends in are compared
 * with the trajectory.
 *
 * <p>The conditions the trajectories were produced under: one Knight of the top side and the six
 * crown towers on the standard arena, nothing else, and a deployment that ends in the moving state
 * after twenty ticks.
 */
class TargetingReplayTest {

  private static final int ARENA_CELLS_WIDE = 36;
  private static final int ARENA_CELLS_HIGH = 64;
  private static final int FIRST_MOVING_TICK = 20;

  private static final int KNIGHT_RANGE = 1200;
  private static final int KNIGHT_SIGHT_RANGE = 5500;
  private static final int KNIGHT_COLLISION_RADIUS = 500;
  private static final int KNIGHT_HIT_SPEED_MS = 1200;
  private static final int KNIGHT_LOAD_TIME_MS = 700;

  /**
   * The six crown towers of the standard arena in placement order - king, left princess, right
   * princess for the bottom side and then the same for the top side - with their positions in game
   * units and their collision radii. The trajectories were produced with exactly this layout.
   */
  private static final List<TowerFixture> TOWERS =
      List.of(
          new TowerFixture("KingTower_0_0", 9000, 3000, 0, true),
          new TowerFixture("PrincessTower_0_1", 3500, 6500, 0, false),
          new TowerFixture("PrincessTower_0_2", 14500, 6500, 0, false),
          new TowerFixture("KingTower_1_0", 9000, 29000, 1, true),
          new TowerFixture("PrincessTower_1_1", 3500, 25500, 1, false),
          new TowerFixture("PrincessTower_1_2", 14500, 25500, 1, false));

  /** One crown tower of the standard layout. */
  private record TowerFixture(String name, int x, int y, int side, boolean king) {}

  /** One recorded tick. */
  private record Record(int tick, int x, int y, int state, String reference) {}

  /** One replay, with everything it needs already wired. */
  private static final class Replay {
    private final List<Record> records = new ArrayList<>();
    private final List<GridEntity> entities = new ArrayList<>();
    private final SpatialIndex index = new SpatialIndex(ARENA_CELLS_WIDE, ARENA_CELLS_HIGH);
    private final TileMap arena = TileMap.standard1v1();
    private final TargetingState state = new TargetingState();
    private final SelectionChain chain;
    private final GridEntity unit = new GridEntity();
    private final MovementState movement = new MovementState();
    private final List<Integer> hitTicks = new ArrayList<>();
    private int tick;

    Replay(JsonNode fixture) {
      for (JsonNode node : fixture.get("records")) {
        records.add(
            new Record(
                node.get("tick").asInt(),
                node.get("x").asInt(),
                node.get("y").asInt(),
                node.get("state").asInt(),
                node.get("ref").isNull() ? null : node.get("ref").asText()));
      }
      int side = fixture.get("side").asInt();

      unit.setName("owner");
      unit.setId(7);
      unit.setSide(side);
      unit.setX(fixture.get("deploy").get(0).asInt());
      unit.setY(fixture.get("deploy").get(1).asInt());
      unit.setLane(fixture.get("lane").asInt());
      unit.setCollisionRadius(KNIGHT_COLLISION_RADIUS);
      unit.setState(GridEntityState.MOVING);
      unit.setMovementActive(true);
      unit.setTargetable(1);

      state.setOwner(unit);
      state.setConfig(
          TargetingConfig.forUnit(
              KNIGHT_RANGE,
              KNIGHT_SIGHT_RANGE,
              KNIGHT_COLLISION_RADIUS,
              KNIGHT_HIT_SPEED_MS,
              KNIGHT_LOAD_TIME_MS,
              true,
              false));
      state.setMovementComponentActive(true);

      chain = new SelectionChain(index, state, ARENA_CELLS_HIGH);
      chain.setHitSink(
          (target, sequenceIndex, extra, last) -> {
            hitTicks.add(tick);
            return false;
          });

      int id = 1;
      for (TowerFixture fixtureTower : TOWERS) {
        boolean king = fixtureTower.king();

        GridEntity tower = new GridEntity();
        tower.setName(fixtureTower.name());
        tower.setId(id++);
        tower.setSide(fixtureTower.side());
        tower.setX(fixtureTower.x());
        tower.setY(fixtureTower.y());
        tower.setCollisionRadius(king ? 1400 : 1000);
        tower.setBuilding(true);
        tower.setCrownTower(true);
        tower.setKingCandidate(king ? 1 : 0);
        tower.setLane(
            LaneAssignment.lane(
                arena.width(),
                arena.height(),
                arena.width(),
                tower.getX(),
                tower.getY(),
                -1,
                0,
                arena::bits));
        tower.setTargetable(1);
        entities.add(tower);

        TargetView view =
            new TargetView(
                tower,
                king
                    ? TargetingConfig.tower("KingTower", 7000, 7000, 1400, 1000, 500, false)
                    : TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));
        if (fixtureTower.side() != side) {
          chain.registerTower(view);
          if (king) {
            chain.setSeed(view);
          }
        } else {
          chain.register(view);
        }
      }
      unit.setId(entities.size() + 1);
      entities.add(unit);
      chain.register(new TargetView(unit, state.getConfig()));
    }

    /** Runs one tick of the targeting pass with the unit standing at the given position. */
    void runTick(int tickNumber, int x, int y, int entryState) {
      this.tick = tickNumber;
      unit.setX(x);
      unit.setY(y);
      unit.setState(entryState);
      index.rebuild(entities);
      chain.beginTick();
      TargetingVisit.targetingVisit(state, unit, movement, chain, chain.getOutcome());
      if (chain.getOutcome().isResumeRequested() && unit.getState() != GridEntityState.ATTACKING) {
        unit.setState(GridEntityState.MOVING);
      }
      index.clear();
      // The state visit that follows the targeting pass adds one step to the unit's elapsed time
      // in every state the replay runs through; the default selection reads it on the next tick.
      unit.setDelay(unit.getDelay() + TargetingQueries.TICK_MS);
    }

    /** Replays every recorded tick from the first moving one, checking the target and the state. */
    void replay() {
      for (int i = FIRST_MOVING_TICK; i < records.size(); i++) {
        Record previous = records.get(i - 1);
        Record expected = records.get(i);
        int entryState =
            previous.state() == GridEntityState.DEPLOYING
                ? GridEntityState.MOVING
                : previous.state();
        runTick(expected.tick(), previous.x(), previous.y(), entryState);

        String chosen = state.getReference() == null ? null : state.getReference().name();
        assertThat(chosen)
            .as("reference at tick %d", expected.tick())
            .isEqualTo(expected.reference());
        assertThat(unit.getState())
            .as("state at tick %d", expected.tick())
            .isEqualTo(expected.state());
      }
    }

    /** Keeps visiting with the unit standing still, which is what an attacking unit does. */
    void keepAttacking(int throughTick) {
      Record last = records.get(records.size() - 1);
      for (int t = last.tick() + 1; t <= throughTick; t++) {
        runTick(t, last.x(), last.y(), GridEntityState.ATTACKING);
      }
    }
  }

  private static Replay load(String name) throws IOException {
    try (InputStream in =
        TargetingReplayTest.class.getResourceAsStream("/pathfinding/golden/" + name + ".json")) {
      assertThat(in).as("fixture %s", name).isNotNull();
      return new Replay(new ObjectMapper().readTree(in));
    }
  }

  @Test
  @DisplayName("a unit deployed on the left keeps the left princess tower and locks on at tick 235")
  void leftDeployment() throws IOException {
    Replay replay = load("knight_left");

    replay.replay();

    assertThat(replay.records.get(FIRST_MOVING_TICK).reference()).isEqualTo("PrincessTower_1_1");
    assertThat(firstAttackingTick(replay)).isEqualTo(235);
  }

  @Test
  @DisplayName(
      "a unit deployed inside the left lane near the middle takes its lane's tower for ten ticks,"
          + " the king from tick 30 and the princess tower again from tick 68")
  void innerLeftDeployment() throws IOException {
    Replay replay = load("knight_left_inner");

    replay.replay();

    assertThat(replay.records.get(FIRST_MOVING_TICK).reference()).isEqualTo("PrincessTower_1_1");
    assertThat(replay.records.get(30).reference()).isEqualTo("KingTower_1_0");
    assertThat(replay.records.get(68).reference()).isEqualTo("PrincessTower_1_1");
    assertThat(firstAttackingTick(replay)).isEqualTo(259);
  }

  @Test
  @DisplayName(
      "a unit deployed on the right keeps the right princess tower and locks on at tick 235")
  void rightDeployment() throws IOException {
    Replay replay = load("knight_right");

    replay.replay();

    assertThat(replay.records.get(FIRST_MOVING_TICK).reference()).isEqualTo("PrincessTower_1_2");
    assertThat(firstAttackingTick(replay)).isEqualTo(235);
  }

  @Test
  @DisplayName("a unit deployed in the middle starts at the king tower and switches at tick 82")
  void centreDeployment() throws IOException {
    Replay replay = load("knight_centre");

    replay.replay();

    assertThat(replay.records.get(FIRST_MOVING_TICK).reference()).isEqualTo("KingTower_1_0");
    assertThat(replay.records.get(81).reference()).isEqualTo("KingTower_1_0");
    assertThat(replay.records.get(82).reference()).isEqualTo("PrincessTower_1_2");
    assertThat(firstAttackingTick(replay)).isEqualTo(245);
  }

  @Test
  @DisplayName("a unit deployed behind the right tower keeps it in sight and locks on at tick 323")
  void rightRearDeployment() throws IOException {
    Replay replay = load("knight_right_rear");

    replay.replay();

    assertThat(replay.records.get(FIRST_MOVING_TICK).reference()).isEqualTo("PrincessTower_1_2");
    assertThat(firstAttackingTick(replay)).isEqualTo(323);
  }

  @Test
  @DisplayName("a unit deployed behind its king tower starts at the king tower and switches at 73")
  void behindKingDeployment() throws IOException {
    Replay replay = load("knight_behind_king");

    replay.replay();

    assertThat(replay.records.get(FIRST_MOVING_TICK).reference()).isEqualTo("KingTower_1_0");
    assertThat(replay.records.get(72).reference()).isEqualTo("KingTower_1_0");
    assertThat(replay.records.get(73).reference()).isEqualTo("PrincessTower_1_2");
    assertThat(firstAttackingTick(replay)).isEqualTo(368);
  }

  @Test
  @DisplayName("the first hit lands 9 ticks after the unit locks on: the load is credited at once")
  void firstHitFollowsTheLock() throws IOException {
    Replay replay = load("knight_left");
    replay.replay();

    assertThat(replay.hitTicks).isEmpty();

    // Lock at 235 with a 700 ms load run down: the attack time starts at 700 and reaches the
    // 1200 ms hit speed on the tenth attack tick, the reference run's tick 244.
    replay.keepAttacking(244);

    assertThat(replay.hitTicks).containsExactly(244);
  }

  private static int firstAttackingTick(Replay replay) {
    return replay.records.stream()
        .filter(r -> r.state() == GridEntityState.ATTACKING)
        .findFirst()
        .orElseThrow()
        .tick();
  }
}
