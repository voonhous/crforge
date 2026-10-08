package org.crforge.core.pathfinding;

import java.util.ArrayList;
import java.util.List;
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
        "Answers the movement pass from the live grid and the unit's own state; held by the walks"
            + " of the reference battles. Supplied: every map cell an acceptable endpoint, no"
            + " status effects in the speed inputs, and a unit that always carries both"
            + " components. Given its buffs, as a battle's unit is, it carries the modifier"
            + " component, and the follower's time step is scaled by them, held by the reference"
            + " battles card_Clone and spell_clone_into_push. The ground and hovering tests read"
            + " the unit's layer from its tag word and live height, never its row: a knocked unit"
            + " under FORCE_IS_AIR takes the single-node route an air unit takes, held by"
            + " cg_megaknight_evo_uppercut_giant; a hovering unit under either force tag hovers"
            + " no longer, held by GridMovementAnswersTest.")
public final class GridMovementQueries implements MovementQueries {

  /** Every relocation this visit asked for, in order. */
  private final List<int[]> relocations = new ArrayList<>();

  private final GridUnitState unit;
  private final CellGrid grid;
  private final CellCosts costs;
  private final Function<GridEntity, GridUnitState> neighbourStates;
  private final ReferencePoint reference;

  /** The speed percents of the unit's buffs, which the speed budget scales by. */
  private int[] speedPercents = new int[0];

  /** The follower's time step, 100 scaled by the unit's buffs. */
  private int followerStep = 100;

  /**
   * Whether the unit carries the buff list, the modifier component the follower's time step is
   * scaled through: given with its buffs.
   */
  private boolean modifierComponent;

  /** The charge range the unit's buffs give it, 0 for none: given with its buffs. */
  private int chargeRangeFromModifiers;

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

  /**
   * Gives the answers the unit's buffs: the speed percents the budget scales by, the follower's
   * time step and the charge range a buff overrides, which makes the unit one with the modifier
   * component. That component's other answer, a facing held still, comes from no buff the battle
   * models, so it stays 0.
   *
   * @param speedPercents the speed percent of each listed buff
   * @param followerStep 100 scaled by the buffs' speed
   * @param chargeRange the charge range of the first listed buff that sets one, or 0 for none
   * @return these answers
   */
  public GridMovementQueries withBuffs(int[] speedPercents, int followerStep, int chargeRange) {
    this.speedPercents = speedPercents.clone();
    this.followerStep = followerStep;
    this.chargeRangeFromModifiers = chargeRange;
    this.modifierComponent = true;
    return this;
  }

  @Override
  public boolean hasModifierComponent() {
    return modifierComponent;
  }

  @Override
  public int chargeRangeFromModifiers() {
    return chargeRangeFromModifiers;
  }

  @Override
  public int scaledTimeStep() {
    return followerStep;
  }

  @Override
  public Route search(int startCol, int startRow, int goalCol, int goalRow, int adjust) {
    GridEntity entity = unit.entity();
    // A hovering unit, and one that jumps the river, may route over water, at the water cost.
    return GridSearchService.route(
        grid,
        costs,
        entity.getState(),
        entity.getLane(),
        hovers(),
        unit.movementConfig().jumpEnabled(),
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

  /** 1 for a hovering unit, which a push does not move off water. */
  @Override
  public int hovering() {
    return hovers() ? 1 : 0;
  }

  /**
   * Whether the unit hovers, as its layer slot answers it: never while either force tag is in its
   * tag word, else as its row says.
   */
  private boolean hovers() {
    EntityFlags bits = unit.entity().getFlagBits();
    long forced = bits.forceIsAir() | bits.forceIsGround();
    return (unit.entity().getFlags() & forced) == 0 && unit.movementConfig().hovering();
  }

  @Override
  public int relocate(int x, int y) {
    int packed = Relocation.relocate(grid.getWidth(), grid.getHeight(), x, y, -1, grid::water);
    relocations.add(new int[] {x, y, packed & 0xffff, packed >> 16});
    return packed;
  }

  /** Every relocation this visit asked for, in order, as {@code {x, y, toX, toY}}. */
  public List<int[]> relocations() {
    return relocations;
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
        entity.getFlagBits(),
        entity.getState(),
        true,
        targeting.getDashWindupMs(),
        targeting.getAttackBlockTimerMs(),
        targeting.isSpecialLoadPending() ? 1 : 0,
        entity.getBlockCountdownMs(),
        speedPercents,
        true,
        unit.movement().getChargeProgress());
  }

  /** The strike-now byte is looked up only while the targeting component is switched on. */
  @Override
  public boolean targetingLookup() {
    return unit.targeting().isTargetingComponentActive();
  }

  @Override
  public void setChargeStrike(boolean set) {
    unit.targeting().setChargeStrike(set);
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

  /**
   * The point a flying unit with direct paths heads for: its attack range away from its reference,
   * on the line from the reference to where the unit stands as the selector asks. A unit standing
   * on the reference's point takes the line along the arena's length.
   */
  @Override
  public int[] specialWaypoint() {
    GridEntity owner = unit.entity();
    int[] toward = {owner.getX() - reference.x(), owner.getY() - reference.y()};
    if (FixedMath.guardedDistance(toward[0], toward[1]) == 0) {
      toward[1] = 1;
    }
    FixedMath.normalize(toward, attackRange());
    return new int[] {reference.x() + toward[0], reference.y() + toward[1]};
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
