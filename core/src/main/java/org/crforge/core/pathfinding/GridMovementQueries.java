package org.crforge.core.pathfinding;

import java.util.function.Function;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.CellCosts;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.CellTests;
import org.crforge.core.pathfinding.grid.GridSearchService;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.ReferenceEndpoint;
import org.crforge.core.pathfinding.grid.Relocation;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.GridMoveEntity;
import org.crforge.core.pathfinding.move.MovementGates;
import org.crforge.core.pathfinding.move.MovementQueries;
import org.crforge.core.pathfinding.move.ReferencePoint;
import org.crforge.core.pathfinding.move.RouteBeyondReference;
import org.crforge.core.pathfinding.move.SpeedBudget;
import org.crforge.core.pathfinding.move.SpeedGlobals;
import org.crforge.core.pathfinding.move.SpeedInputs;
import org.crforge.core.pathfinding.target.AttackRange;
import org.crforge.core.pathfinding.target.RangeTest;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * The movement pass's view of the rest of the simulation for one troop's visit.
 *
 * <p>Everything geometric is answered from the routing grid and the troop's own live state, so an
 * answer asked for after the pass has already moved the troop reflects the new position.
 *
 * <p>One instance serves one visit: the reference point is read once, when the instance is built.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Answers the movement pass from the live grid and the unit's own state; held by"
            + " the 53 reference walks. Supplied: both water permissions off, every map cell"
            + " an acceptable endpoint, no status effects in the speed inputs, and a unit that"
            + " always carries both components.")
public final class GridMovementQueries implements MovementQueries {

  private final GridUnitState unit;
  private final CellGrid grid;
  private final CellCosts costs;
  private final Function<GridEntity, GridUnitState> neighbourStates;
  private final ReferencePoint reference;

  /** The last budget this visit asked for, kept so the tick driver can report it afterwards. */
  private int lastSpeedBudget;

  /** The last endpoint this visit asked for, kept so the tick driver can report it afterwards. */
  private int lastEndpoint = -1;

  /**
   * Creates the answers for one visit of one unit.
   *
   * @param unit the unit whose movement pass is about to run
   * @param grid the routing grid with this tick's building overlay
   * @param costs the cell costs the route search runs with
   * @param neighbourStates the grid state of another unit driven by the same rules, or null for an
   *     entity that is not
   */
  public GridMovementQueries(
      GridUnitState unit,
      CellGrid grid,
      CellCosts costs,
      Function<GridEntity, GridUnitState> neighbourStates) {
    this.unit = unit;
    this.grid = grid;
    this.costs = costs;
    this.neighbourStates = neighbourStates;
    TargetView target = unit.targeting().getReference();
    this.reference = target == null ? null : new ReferencePoint(target.x(), target.y());
  }

  public ReferencePoint reference() {
    return reference;
  }

  @Override
  public Route search(int startCol, int startRow, int goalCol, int goalRow, int adjust) {
    GridEntity entity = unit.entity();
    // Both water permissions are off: a plain ground unit may not route through the river, and
    // the units that may are not managed here.
    return GridSearchService.route(
        grid,
        costs,
        entity.getState(),
        entity.getLane(),
        false,
        false,
        startCol,
        startRow,
        goalCol,
        goalRow,
        adjust);
  }

  @Override
  public int endpoint(int referenceCol, int referenceRow, int radius) {
    GridEntity entity = unit.entity();
    ReferencePoint point = reference;
    if (point == null) {
      return -1;
    }
    lastEndpoint =
        ReferenceEndpoint.selectEndpoint(
            grid.getWidth(),
            grid.getHeight(),
            entity.getX(),
            entity.getY(),
            referenceCol,
            referenceRow,
            radius,
            entity.isAir(),
            PathfindingGlobals.KS_POS_TO_TARGET_FLYING_NO_WATER,
            PathfindingGlobals.KS_POS_TO_TARGET_GROUND_AVOID_BUILDINGS,
            ReferenceEndpoint.everyCellInBounds(grid.getWidth(), grid.getHeight()),
            (x, y) -> FixedMath.squaredDistance(point.x(), point.y(), x, y),
            grid::water,
            (col, row) -> CellTests.overlayBlocks(grid, col, row),
            null);
    return lastEndpoint;
  }

  /** The last endpoint this visit asked for, or -1 when it asked for none. */
  public int lastEndpoint() {
    return lastEndpoint;
  }

  @Override
  public int relocate(int x, int y) {
    return Relocation.relocate(grid.getWidth(), grid.getHeight(), x, y, -1, grid::water);
  }

  @Override
  public int cellTest(int worldX, int worldY) {
    return CellTests.cellBlocked(grid, worldX, worldY);
  }

  /**
   * The speed and gate inputs, read live from the entity and its two components.
   *
   * <p>The standard game freezes these once, before the visit, and answers every gate from that
   * snapshot. Nothing inside a movement visit writes the state, the flags or the charge progress,
   * so reading them live gives the same answers; a pass that started writing them mid-visit would
   * make the two differ.
   *
   * <p>Two of the inputs are supplied rather than read: the entity is always said to carry a
   * targeting component and a movement component. Both are true of every unit these answers are
   * built for, and a unit for which either was false would have to say so here.
   */
  private SpeedInputs speedInputs() {
    GridEntity entity = unit.entity();
    TargetingState targeting = unit.targeting();
    return new SpeedInputs(
        entity.getFlags(),
        entity.getState(),
        true,
        targeting.getDashWindupMs(),
        targeting.getAttackBlockTimerMs(),
        targeting.isSpecialLoadPending() ? 1 : 0,
        entity.getBlockCountdownMs(),
        // Status effects do not feed the grid speed budget yet.
        new int[0],
        true,
        unit.movement().getChargeProgress());
  }

  @Override
  public int speedBudget() {
    lastSpeedBudget =
        SpeedBudget.speedBudget(speedInputs(), unit.speedConfig(), SpeedGlobals.standard());
    return lastSpeedBudget;
  }

  /** The last budget this visit asked for, zero when it asked for none. */
  public int lastSpeedBudget() {
    return lastSpeedBudget;
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
    return MovementGates.pushGate(speedInputs(), unit.speedConfig());
  }

  @Override
  public int routeRequest() {
    int state = unit.entity().getState();
    return state == GridEntityState.MOVING
            || state == GridEntityState.SPAWN_PATHFIND
            || state == GridEntityState.INGAME_PATHFIND
        ? 1
        : 0;
  }

  @Override
  public int attackRange() {
    return AttackRange.attackRangeWithRadius(unit.targeting());
  }

  @Override
  public int farther() {
    return RouteBeyondReference.routeBeyondReference(
        unit.movement(), unit.entity(), reference, grid.getWidth());
  }

  @Override
  public int ownerSide() {
    return unit.entity().getSide();
  }

  @Override
  public int air() {
    return unit.entity().isAir() ? 1 : 0;
  }

  @Override
  public int ground() {
    return unit.entity().isAir() ? 0 : 1;
  }

  @Override
  public int referenceAvailable() {
    return reference == null ? 0 : 1;
  }

  @Override
  public int referenceInRange() {
    TargetingState targeting = unit.targeting();
    TargetView target = targeting.getReference();
    if (target == null) {
      return 0;
    }
    return RangeTest.referenceInRange(targeting, target, 0) ? 1 : 0;
  }

  @Override
  public int gridWidth() {
    return grid.getWidth();
  }

  @Override
  public GridMoveEntity entityView() {
    return GridMoveEntity.of(unit.entity(), unit.movementConfig());
  }

  /**
   * The blend of a neighbour this system also drives. A neighbour it does not drive - an air unit,
   * a jumping unit, a building - has no blend of its own and answers zero, which makes the
   * avoidance handler fall back to the side the neighbour stands on.
   */
  @Override
  public int neighbourAvoidanceBlend(GridEntity other) {
    GridUnitState state = neighbourStates.apply(other);
    return state == null ? 0 : state.movement().getAvoidanceBlend();
  }

  /**
   * Whether a neighbour this system also drives has a special attack loaded. A neighbour it does
   * not drive answers zero, as an entity with no targeting component does.
   */
  @Override
  public int neighbourSpecialLoadPending(GridEntity other) {
    GridUnitState state = neighbourStates.apply(other);
    return state != null && state.targeting().isSpecialLoadPending() ? 1 : 0;
  }
}
