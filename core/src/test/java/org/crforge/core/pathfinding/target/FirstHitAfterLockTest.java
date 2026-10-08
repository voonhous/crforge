package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The first hit after a lock, run through the targeting pass: a unit written standing inside a
 * princess tower's reach locks on, and its first hit follows the lock by the hit speed less the
 * load, because an attack starting from zero is credited the whole of a run-down load at once.
 *
 * <p>The scene is written here: the six crown towers of the standard arena at their places and
 * radii, and one Knight-like unit of the bottom side in the right lane, put down well inside the
 * top right princess tower's reach. Only the targeting pass runs; the unit does not move.
 */
class FirstHitAfterLockTest {

  private static final int ARENA_CELLS_WIDE = 36;
  private static final int ARENA_CELLS_HIGH = 64;

  /** The unit's targeting columns, written here: a Knight's range, sight, radius and timers. */
  private static final int RANGE = 1200;

  private static final int SIGHT_RANGE = 5500;
  private static final int COLLISION_RADIUS = 500;
  private static final int HIT_SPEED_MS = 1200;
  private static final int LOAD_TIME_MS = 700;

  /** The radius the princess towers are written with. */
  private static final int PRINCESS_RADIUS = 1000;

  /**
   * The six crown towers of the standard arena in placement order - king, left princess, right
   * princess for the bottom side and then the same for the top side - with their positions in game
   * units.
   */
  private static final List<TowerFixture> TOWERS =
      List.of(
          new TowerFixture("KingTower_0_0", 9000, 3000, 0, true),
          new TowerFixture("PrincessTower_0_1", 3500, 6500, 0, false),
          new TowerFixture("PrincessTower_0_2", 14500, 6500, 0, false),
          new TowerFixture("KingTower_1_0", 9000, 29000, 1, true),
          new TowerFixture("PrincessTower_1_1", 3500, 25500, 1, false),
          new TowerFixture("PrincessTower_1_2", 14500, 25500, 1, false));

  /** The tower the unit stands under. */
  private static final TowerFixture TARGET = TOWERS.get(5);

  /** The unit stands straight below it, half its range from the tower's edge. */
  private static final int STAND_X = TARGET.x();

  private static final int STAND_Y = TARGET.y() - PRINCESS_RADIUS - RANGE / 2;

  /** The right lane, the lane of the tower it stands under. */
  private static final int LANE = 2;

  /** One crown tower of the standard layout. */
  private record TowerFixture(String name, int x, int y, int side, boolean king) {}

  private final List<GridEntity> entities = new ArrayList<>();
  private final SpatialIndex index = new SpatialIndex(ARENA_CELLS_WIDE, ARENA_CELLS_HIGH);
  private final TileMap arena = TileMap.standard1v1();
  private final TargetingState state = new TargetingState();
  private final GridEntity unit = new GridEntity();
  private final MovementState movement = new MovementState();
  private final List<Integer> hitTicks = new ArrayList<>();
  private SelectionChain chain;
  private int tick;

  /** Writes the six towers and the unit, and wires the selection chain over them. */
  private void writeScene() {
    unit.setName("owner");
    unit.setSide(0);
    unit.setX(STAND_X);
    unit.setY(STAND_Y);
    unit.setLane(LANE);
    unit.setCollisionRadius(COLLISION_RADIUS);
    unit.setState(GridEntityState.MOVING);
    unit.setMovementActive(true);
    unit.setTargetable(1);

    state.setOwner(unit);
    state.setConfig(
        TargetingConfig.forUnit(
            RANGE, SIGHT_RANGE, COLLISION_RADIUS, HIT_SPEED_MS, LOAD_TIME_MS, true, false));
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
      tower.setCollisionRadius(king ? 1400 : PRINCESS_RADIUS);
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
                  : TargetingConfig.tower(
                      "PrincessTower", 7500, 7500, PRINCESS_RADIUS, 800, 0, true));
      if (fixtureTower.side() != unit.getSide()) {
        // The princess towers are the candidates; the king only seeds the selection.
        if (king) {
          chain.register(view);
          chain.setSeed(view);
        } else {
          chain.registerTower(view);
        }
      } else {
        chain.register(view);
      }
    }
    unit.setId(entities.size() + 1);
    entities.add(unit);
    chain.register(new TargetView(unit, state.getConfig()));
  }

  /** Runs one tick of the targeting pass with the unit standing where it was written. */
  private void runTick(int tickNumber) {
    this.tick = tickNumber;
    index.rebuild(entities);
    chain.beginTick();
    TargetingVisit.targetingVisit(state, unit, movement, chain, chain.getOutcome());
    if (chain.getOutcome().isResumeRequested() && unit.getState() != GridEntityState.ATTACKING) {
      unit.setState(GridEntityState.MOVING);
    }
    index.clear();
    // The state visit that follows the targeting pass adds one step to the unit's elapsed time;
    // the default selection reads it on the next tick.
    unit.setDelay(unit.getDelay() + TargetingQueries.TICK_MS);
  }

  @Test
  @DisplayName("the first hit lands 9 ticks after the unit locks on: the load is credited at once")
  void firstHitFollowsTheLock() {
    writeScene();

    // The tick it locks on is found by running; nothing is hit before it.
    int lockTick = -1;
    for (int t = 0; t < 20 && lockTick < 0; t++) {
      runTick(t);
      if (unit.getState() == GridEntityState.ATTACKING) {
        lockTick = t;
      }
    }
    assertThat(lockTick).as("the unit locks on within its first 20 ticks").isNotNegative();
    assertThat(state.getReference().name()).isEqualTo(TARGET.name());
    assertThat(hitTicks).as("no hit on the lock's tick").isEmpty();

    // With its load run down the attack time starts at the load and reaches the hit speed on the
    // tenth attack tick, the lock's own tick counted first.
    int firstHit = lockTick + (HIT_SPEED_MS - LOAD_TIME_MS) / TargetingQueries.TICK_MS - 1;
    for (int t = lockTick + 1; t <= firstHit; t++) {
      runTick(t);
    }

    assertThat(firstHit - lockTick).isEqualTo(9);
    assertThat(hitTicks).containsExactly(firstHit);
    assertThat(unit.getState()).isEqualTo(GridEntityState.ATTACKING);
  }
}
