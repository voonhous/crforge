package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.GridMovementQueries;
import org.crforge.core.pathfinding.GridStateSetter;
import org.crforge.core.pathfinding.GridUnitState;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.move.MovementChain;
import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.MovementVisit;
import org.crforge.core.pathfinding.move.SpeedConfig;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.ResumeHelper;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.TargetingVisit;

/**
 * A walking ground unit: a targeting component in slot 0, a movement component in slot 1 and the
 * entity state visit as its post-hook.
 *
 * <p>Because the holder runs one whole-list pass per slot, every character has chosen its target
 * for the tick before any character moves, and every character has moved before any character's
 * state visit runs. A character is created deploying and takes no decisions until its deploy
 * countdown, stepped by the state visit, runs out.
 *
 * <p>Every state change - the lock the targeting visit requests, the resume, the state visit's
 * transitions - goes through the character's own {@link GridStateSetter}, so stopping empties the
 * route and resuming prepares one at once; taking a reference prepares its route through the same
 * setter, at the moment the reference is stored.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: targeting in slot 0, movement in slot 1, the state visit as the post-hook, the"
            + " deploy countdown stepping 50 ms per state visit, and every state change and"
            + " route preparation going through the unit's own setter. Supplied, not settled:"
            + " both components return at once while the character is deploying. Not modelled"
            + " yet: air, jumping and hovering units, hits landing on the target, status effects"
            + " on the speed budget, the deployment's own lane flag, and the columns that"
            + " restrict what a unit may target, such as buildings only, which its data does not"
            + " carry.")
public class CharacterEntity extends WorldEntity {

  /** Slot of the targeting component. */
  public static final int TARGETING_SLOT = 0;

  /** Slot of the movement component. */
  public static final int MOVEMENT_SLOT = 1;

  private final BattleWorld world;

  /** The working state of the character's two components and its state visit. */
  @Getter private final GridUnitState unit;

  /** Applies every state change the character asks for, with the actions the change carries. */
  private final GridStateSetter setter;

  /** True once the opposing side's towers have been registered as default targets. */
  private boolean towersRegistered;

  /**
   * Creates a character at its deploy position, deploying.
   *
   * @param world the battle's shared arena state
   * @param data the character's published columns; only ground units are supported
   * @param name the character's unique name within the battle
   * @param side the side that owns the character
   * @param x deploy position in game units
   * @param y deploy position in game units
   */
  public CharacterEntity(BattleWorld world, UnitData data, String name, int side, int x, int y) {
    super(data, createView(world.getTileMap(), data, name, side, x, y), targetingConfig(data));
    checkArgument(!data.air() && !data.building(), () -> data.name() + " is not a ground unit");
    this.world = world;

    GridEntity view = getView();
    TargetingState targeting = new TargetingState();
    targeting.setOwner(view);
    targeting.setConfig(getTargetView().getConfig());
    targeting.setMovementComponentActive(true);
    this.unit =
        new GridUnitState(
            view,
            MovementState.forSide(side, x, y),
            targeting,
            new StateTimers(),
            MovementConfig.forGroundUnit(),
            SpeedConfig.forGroundUnit(data.speed()),
            StateVisitConfig.forGroundUnit(data.deployTimeMs()),
            new SelectionChain(world.getIndex(), targeting, world.getTileMap().height()),
            getTargetView());
    this.setter = new GridStateSetter(view, unit.movement(), targeting, this::movementChain);
    unit.selection().setStateSetter(setter);
    unit.selection().getOutcome().setRoutePreparer(setter::prepareRoute);

    attach(new TargetingComponent());
    attach(new MovementComponent());
  }

  /**
   * The character's view at placement: deploying, facing up the arena from the bottom side and down
   * it from the top side, in the lane of the road nearest to the deploy position.
   */
  private static GridEntity createView(
      TileMap tileMap, UnitData data, String name, int side, int x, int y) {
    GridEntity view = new GridEntity();
    view.setName(name);
    view.setSide(side);
    view.setCollisionRadius(data.collisionRadius());
    view.setMass(data.mass());
    view.setMovementActive(true);
    view.setPushEnabled(true);
    view.setTargetable(1);
    view.setX(x);
    view.setY(y);
    view.setLane(
        LaneAssignment.lane(
            tileMap.width(), tileMap.height(), tileMap.width(), x, y, -1, 0, tileMap::bits));
    view.setState(GridEntityState.DEPLOYING);
    view.setDeployCountdown(data.deployTimeMs());
    view.setDirX(0);
    view.setDirY(
        side == SIDE_BOTTOM ? MovementState.DIRECTION_SCALE : -MovementState.DIRECTION_SCALE);
    return view;
  }

  private static TargetingConfig targetingConfig(UnitData data) {
    return TargetingConfig.forUnit(
            data.range(),
            data.sightRange(),
            data.collisionRadius(),
            data.hitSpeedMs(),
            data.loadTimeMs(),
            data.attacksGround(),
            data.attacksAir())
        .toBuilder()
        .configKey(data.name())
        .build();
  }

  /**
   * Makes every arena entity of this tick known to the character's selection. The first call also
   * registers the opposing side's towers as the default targets, in creation order - king first,
   * then the princess towers along the arena's width - and seeds the selection with the king.
   */
  void registerCandidates(List<WorldEntity> present) {
    SelectionChain selection = unit.selection();
    if (!towersRegistered) {
      towersRegistered = true;
      int enemy = opposing(side());
      for (WorldEntity entity : present) {
        if (entity instanceof TowerEntity tower && tower.side() == enemy) {
          selection.registerTower(tower.getTargetView());
          if (tower.getData().king() && selection.getSeed() == null) {
            selection.setSeed(tower.getTargetView());
          }
        }
      }
    }
    for (WorldEntity entity : present) {
      if (selection.view(entity.getView()) == null) {
        selection.register(entity.getTargetView());
      }
    }
  }

  /** Drops an entity that has left the battle from the character's selection. */
  void forget(GridEntity departed) {
    unit.selection().unregister(departed);
  }

  private boolean deploying() {
    return getView().getState() == GridEntityState.DEPLOYING;
  }

  private StateQueries stateQueries() {
    return StateQueries.forUnitWithRoute(side() & 1);
  }

  /** The movement pass's answers for the character as it stands now, reference included. */
  private GridMovementQueries movementQueries() {
    return new GridMovementQueries(unit, world.getGrid(), world.getCosts(), world::unitStateOf);
  }

  /** A movement chain over the character's current reference, for one visit or one preparation. */
  private MovementChain movementChain() {
    return movementChain(movementQueries());
  }

  private MovementChain movementChain(GridMovementQueries queries) {
    return new MovementChain(
        unit.movement(),
        getView(),
        world.getGrid(),
        unit.movementConfig(),
        world.getMovementGlobals(),
        queries.reference(),
        world.getNeighbourQuery(),
        queries);
  }

  /** The entity state visit: the deploy countdown and every other per-tick state transition. */
  @Override
  protected void postHook() {
    EntityStateVisit.stateVisit(
        getView(),
        unit.timers(),
        unit.movement(),
        unit.stateConfig(),
        StateVisitGlobals.standard(),
        stateQueries(),
        new ArrayList<>(),
        setter);
  }

  /** Chooses, keeps or drops the character's target and decides whether it attacks this tick. */
  private final class TargetingComponent implements BattleComponent {

    @Override
    public int index() {
      return TARGETING_SLOT;
    }

    @Override
    public void visit() {
      if (deploying()) {
        return;
      }
      unit.targeting().setRouteLeadsAway(unit.movement().getRouteLeadsAway() != 0);
      SelectionChain selection = unit.selection();
      selection.beginTick();
      TargetingVisit.targetingVisit(
          unit.targeting(), getView(), unit.movement(), selection, selection.getOutcome());
      if (selection.getOutcome().isResumeRequested()) {
        ResumeHelper.resume(
            getView(), unit.stateConfig(), stateQueries(), new ArrayList<>(), setter);
      }
    }
  }

  /** Prepares and follows the character's route, steers it around others and applies pushes. */
  private final class MovementComponent implements BattleComponent {

    @Override
    public int index() {
      return MOVEMENT_SLOT;
    }

    @Override
    public void visit() {
      if (deploying()) {
        return;
      }
      GridMovementQueries queries = movementQueries();
      MovementVisit.movementVisit(
          unit.movement(),
          getView(),
          null,
          unit.movementConfig(),
          null,
          queries,
          false,
          movementChain(queries));
    }
  }
}
