package org.crforge.core.pathfinding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Movement;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.CellCosts;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.FootprintOverlay;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.move.MovementChain;
import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementGlobals;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.MovementVisit;
import org.crforge.core.pathfinding.move.NeighbourQuery;
import org.crforge.core.pathfinding.move.SpeedConfig;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.ResumeHelper;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.TargetingVisit;
import org.crforge.core.player.Team;
import org.crforge.core.util.GameUnits;

/**
 * Drives the grid movement and targeting rules for the ground troops of one match.
 *
 * <p>The system owns one routing grid with its building overlay, one spatial index, and one view
 * per live entity. Troops it manages carry a {@link GridUnitState}; everything else it only indexes
 * and stamps, so that a managed troop can see it, route around it and attack it.
 *
 * <p>One tick is two calls, in this order and with the simulator's own combat step between them:
 *
 * <ol>
 *   <li>{@link #updateTargeting(Collection)} rebuilds the entity views in creation order, rebuilds
 *       the spatial index from them, builds the footprint overlay and copies the per-side change
 *       flags, and then runs one targeting visit per managed troop that is not deploying, followed
 *       by the resume tail when the visit asks for one.
 *   <li>{@link #updateMovement(Collection)} runs one movement visit per managed troop that is not
 *       deploying, then one entity state visit per managed troop, then writes each troop's position
 *       and facing back, and finally rotates the overlay and clears the index.
 * </ol>
 *
 * <p>Both loops run in entity creation order, which is ascending entity id: the footprint overlay's
 * per-side hash folds ids in that order, and the standard game visits an id-sorted list.
 *
 * <p>What the system writes outside its own state:
 *
 * <ul>
 *   <li>{@code troop.getCombat().setCurrentTarget(...)}, but only when the chosen target differs
 *       from the one the troop already holds, because that setter resets the attack wind-up;
 *   <li>{@code troop.getCombat().setTargetLocked(true)} on every tick the targeting visit has put
 *       the troop into the attacking state. The lock is never cleared here: dropping the reference
 *       clears it through the target setter, and the combat system owns the rest;
 *   <li>{@code troop.getPosition().set(x, y)} with the integer position the movement pass produced,
 *       and {@code setRotation} with the angle of the facing vector.
 * </ul>
 *
 * <p>The troop's own deploy timer is left alone; the grid deploy countdown runs beside it and both
 * are twenty ticks for a one second deploy time.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The older engine's adapter onto these rules. It keeps the entity order and the"
            + " pre-pass and post-pass work, but it splits one tick into two calls with the"
            + " older engine's combat between them and copies targets and positions back,"
            + " which the battle package does not need to do.")
public class GridPathfindingSystem {

  /** Height the push pass reads for an entity that is off the ground. */
  private static final int AIR_HEIGHT = 1;

  /** The arena's routing grid, its static cell map and its live building overlay. */
  @Getter private final CellGrid grid;

  /** The spatial index the target selection queries, rebuilt once per tick. */
  @Getter private final SpatialIndex index;

  private final GameState gameState;
  private final TileMap tileMap;
  private final CellCosts costs = CellCosts.standard();
  private final MovementGlobals movementGlobals;

  /** One view per live entity, keyed by entity id and kept for as long as the entity lives. */
  private final Map<Long, GridEntity> entityViews = new HashMap<>();

  /**
   * One target view per live entity, keyed by entity id; the identity a reference is compared by.
   */
  private final Map<Long, TargetView> targetViews = new HashMap<>();

  /** The simulator entity behind each view, so a chosen reference can be written back to combat. */
  private final Map<Long, Entity> simulatorEntities = new HashMap<>();

  /** This tick's views in creation order: what the index, the overlay and the push pass see. */
  private final List<GridEntity> orderedViews = new ArrayList<>();

  /** This tick's managed troops in creation order. */
  private final List<Troop> managedTroops = new ArrayList<>();

  /** The grid state behind each managed troop's view, so a neighbour's own state can be read. */
  private final Map<GridEntity, GridUnitState> unitStates = new IdentityHashMap<>();

  /**
   * How many neighbours pushed each managed troop during the most recent movement update, keyed by
   * entity id. The displacement zeroes the accumulators as it spends them, so this is what is left
   * to look at afterwards.
   */
  private final Map<Long, Integer> pushContributions = new HashMap<>();

  /**
   * The movement budget each managed troop was given during the most recent movement update, in
   * game units, keyed by entity id.
   */
  private final Map<Long, Integer> lastSpeedBudget = new HashMap<>();

  /**
   * The largest step any displacement of each managed troop was allowed to spend during the most
   * recent movement update, in game units, keyed by entity id.
   */
  private final Map<Long, Integer> lastDisplacementStep = new HashMap<>();

  /**
   * The cell each managed troop's route preparation last chose to stop at, packed as {@code (column
   * << 16) | row}, or -1 when the scan found nothing, keyed by entity id.
   */
  private final Map<Long, Integer> lastEndpoint = new HashMap<>();

  /**
   * Number of ticks this system has driven. It is stepped at the head of {@link
   * #updateTargeting(Collection)}, right after the spatial index and the footprint overlay have
   * been rebuilt, so a caller can tell that both are this tick's.
   */
  @Getter private long tickCount;

  /** The neighbour answers both the push pass and the avoidance handler ask for. */
  private final NeighbourQuery neighbourQuery;

  /**
   * Creates the system for one match.
   *
   * @param gameState the match's entity state, used for the tower lists the default target
   *     selection needs
   * @param tileMap the arena's static routing cell map
   */
  public GridPathfindingSystem(GameState gameState, TileMap tileMap) {
    this.gameState = gameState;
    this.tileMap = tileMap;
    this.grid =
        new CellGrid(
            tileMap,
            PathfindingGlobals.PATHFINDING_DYNAMIC_OCCLUSIONS,
            PathfindingGlobals.PATHFINDING_BUILDING_COST);
    this.index = new SpatialIndex(tileMap.width(), tileMap.height());
    this.neighbourQuery = new IndexNeighbourQuery(index);
    this.movementGlobals = MovementGlobals.forStandardArena(tileMap.width());
  }

  /**
   * True when this system, rather than the targeting and physics systems, owns the troop's target
   * and position.
   *
   * <p>Only plain ground troops qualify. Air units, the five jump-enabled units, hovering units and
   * a unit that is currently jumping or tunnelling keep the simulator's own rules, and so does a
   * unit attached to a parent, whose position is written by the attachment.
   */
  public boolean manages(Troop troop) {
    Movement movement = troop.getMovement();
    return movement != null
        && troop.getMovementType() == MovementType.GROUND
        && !movement.isJumpEnabled()
        && !movement.isHovering()
        && !troop.isJumping()
        && !troop.isTunneling()
        && !troop.isAttached();
  }

  /**
   * The grid state of a troop this system manages, or null when it has never seen the troop.
   *
   * <p>The debug renderer reads the route and the reference through this.
   */
  public GridUnitState stateOf(Troop troop) {
    return troop.getGridUnitState();
  }

  /**
   * How many neighbours pushed this troop during the most recent movement update, or zero when it
   * was not pushed at all or is not managed here.
   */
  public int pushContributions(Troop troop) {
    return pushContributions.getOrDefault(troop.getId(), 0);
  }

  /**
   * The movement budget this troop was given during the most recent movement update, in game units,
   * or zero when it did not move this tick or is not managed here.
   */
  public int speedBudget(Troop troop) {
    return lastSpeedBudget.getOrDefault(troop.getId(), 0);
  }

  /**
   * The largest step any of this troop's displacements was allowed to spend during the most recent
   * movement update, in game units. It is bounded by the movement budget and does not include what
   * a push added on top.
   */
  public int displacementStep(Troop troop) {
    return lastDisplacementStep.getOrDefault(troop.getId(), 0);
  }

  /**
   * The cell this troop's route preparation last chose to stop at to reach its target, packed as
   * {@code (column << 16) | row}, or -1 when the scan found nothing or none was asked for.
   */
  public int referenceEndpoint(Troop troop) {
    return lastEndpoint.getOrDefault(troop.getId(), -1);
  }

  // -----------------------------------------------------------------------------------------
  // The tick
  // -----------------------------------------------------------------------------------------

  /**
   * The pre-pass and the targeting visits: steps 1 and 2 of the tick.
   *
   * @param alive the match's live entities; the order they arrive in does not matter, because the
   *     system sorts them into creation order itself
   */
  public void updateTargeting(Collection<Entity> alive) {
    refreshViews(alive);
    index.rebuild(orderedViews);
    FootprintOverlay.buildOverlay(grid, orderedViews);
    grid.setChangeFlags(grid.getChanged().clone());
    tickCount++;

    for (Troop troop : managedTroops) {
      GridUnitState unit = troop.getGridUnitState();
      GridEntity entity = unit.entity();
      if (entity.getState() == GridEntityState.DEPLOYING) {
        continue;
      }
      MovementState movement = unit.movement();
      unit.targeting().setRouteLeadsAway(movement.getRouteLeadsAway() != 0);

      SelectionChain chain = unit.selection();
      chain.beginTick();
      TargetingVisit.targetingVisit(unit.targeting(), entity, movement, chain, chain.getOutcome());
      if (chain.getOutcome().isResumeRequested()) {
        ResumeHelper.resume(
            entity,
            unit.stateConfig(),
            stateQueries(entity),
            new ArrayList<>(),
            chain.stateSetter());
      }
      writeCombat(troop, unit);
    }
  }

  /**
   * The movement visits, the entity state visits and the post-pass: steps 3 to 5 of the tick.
   *
   * @param alive the match's live entities. The passes run over the snapshot the pre-pass took, in
   *     creation order, the way the standard game visits a list snapshotted at the head of the
   *     tick; the argument is taken so both halves of the tick read the same way at the call site.
   */
  public void updateMovement(Collection<Entity> alive) {
    pushContributions.clear();
    lastSpeedBudget.clear();
    lastDisplacementStep.clear();
    lastEndpoint.clear();
    for (Troop troop : managedTroops) {
      GridUnitState unit = troop.getGridUnitState();
      GridEntity entity = unit.entity();
      // A deploying troop is visited too: its speed is zero, but it is pushed and steered.
      GridMovementQueries queries = movementQueries(unit);
      MovementChain chain = movementChain(unit, queries);
      MovementVisit.movementVisit(
          unit.movement(), entity, null, unit.movementConfig(), null, queries, false, chain);
      pushContributions.put(troop.getId(), chain.pushContributions());
      lastSpeedBudget.put(troop.getId(), queries.lastSpeedBudget());
      lastDisplacementStep.put(troop.getId(), chain.largestDisplacementStep());
      lastEndpoint.put(troop.getId(), queries.lastEndpoint());
    }

    for (Troop troop : managedTroops) {
      GridUnitState unit = troop.getGridUnitState();
      GridEntity entity = unit.entity();
      EntityStateVisit.stateVisit(
          entity,
          unit.timers(),
          unit.movement(),
          unit.stateConfig(),
          StateVisitGlobals.standard(),
          stateQueries(entity),
          new ArrayList<>(),
          unit.selection().stateSetter());
      troop.getPosition().set(entity.getX(), entity.getY());
      if (entity.getDirX() != 0 || entity.getDirY() != 0) {
        troop.getPosition().setRotation((float) Math.atan2(entity.getDirY(), entity.getDirX()));
      }
    }

    grid.swap();
    index.clear();
  }

  // -----------------------------------------------------------------------------------------
  // Views
  // -----------------------------------------------------------------------------------------

  /**
   * Rebuilds this tick's ordered view list from the live entities and brings every view up to date.
   *
   * <p>A view is created the first time an entity is seen and kept, so the identity a reference is
   * compared by survives the whole life of the entity. Views of entities that are no longer alive
   * are dropped from every selection chain and then forgotten: a destroyed tower therefore leaves
   * the candidate lists at the end of the tick it died in.
   */
  private void refreshViews(Collection<Entity> alive) {
    List<Entity> sorted = new ArrayList<>(alive);
    sorted.sort(Comparator.comparingLong(Entity::getId));

    orderedViews.clear();
    managedTroops.clear();
    unitStates.clear();
    Set<Long> seen = new HashSet<>();

    for (Entity entity : sorted) {
      long id = entity.getId();
      seen.add(id);
      GridEntity view = entityViews.get(id);
      if (view == null) {
        view = createView(entity);
        entityViews.put(id, view);
        simulatorEntities.put(id, entity);
        targetViews.put(id, new TargetView(view, configFor(entity)));
      }
      syncView(view, entity);
      orderedViews.add(view);
      if (entity instanceof Troop troop && manages(troop)) {
        if (troop.getGridUnitState() == null) {
          troop.setGridUnitState(createUnitState(troop, view));
        }
        managedTroops.add(troop);
        unitStates.put(view, troop.getGridUnitState());
      }
    }

    dropDeadViews(seen);
    for (Troop troop : managedTroops) {
      syncRegistrations(troop.getGridUnitState());
    }
  }

  /** Forgets every view whose entity is no longer alive, and unregisters it from every chain. */
  private void dropDeadViews(Set<Long> stillAlive) {
    List<Long> gone = new ArrayList<>();
    for (Long id : entityViews.keySet()) {
      if (!stillAlive.contains(id)) {
        gone.add(id);
      }
    }
    if (gone.isEmpty()) {
      return;
    }
    for (Long id : gone) {
      GridEntity view = entityViews.remove(id);
      targetViews.remove(id);
      simulatorEntities.remove(id);
      for (Troop troop : managedTroops) {
        troop.getGridUnitState().selection().unregister(view);
      }
    }
  }

  /** Creates the view of one entity, with the fields that never change over its life. */
  private GridEntity createView(Entity entity) {
    GridEntity view = new GridEntity();
    view.setId((int) entity.getId());
    view.setName(entity.getName());
    view.setSide(side(entity.getTeam()));
    view.setCollisionRadius(entity.getCollisionRadius());
    Movement movement = entity.getMovement();
    view.setMass(movement == null ? 0 : Math.round(movement.getMass()));
    boolean building = entity.getMovementType() == MovementType.BUILDING;
    view.setBuilding(building);
    view.setOccludes(building);
    view.setMovementActive(!building);
    // Every tower is a crown tower to the targeting rules; only the king fills the tower slot.
    view.setCrownTower(entity instanceof Tower);
    view.setAir(entity.getMovementType() == MovementType.AIR);
    view.setKingCandidate(entity instanceof Tower tower && tower.isCrownTower() ? 1 : 0);
    view.setTargetable(1);
    view.setX(entity.getPosition().getX());
    view.setY(entity.getPosition().getY());
    // Every character, the towers included, is given the lane of the road nearest to it; the
    // default selection compares a unit's lane with its candidates'.
    view.setLane(
        LaneAssignment.lane(
            tileMap.width(),
            tileMap.height(),
            tileMap.width(),
            view.getX(),
            view.getY(),
            -1,
            0,
            tileMap::bits));
    if (entity instanceof Troop) {
      view.setState(GridEntityState.DEPLOYING);
      view.setDeployCountdown(deployTimeMs(entity));
      view.setDirX(0);
      view.setDirY(
          view.getSide() == 0 ? MovementState.DIRECTION_SCALE : -MovementState.DIRECTION_SCALE);
    }
    return view;
  }

  /**
   * Brings a view up to date with the entity it describes, at the head of the tick.
   *
   * <p>The position is read back from the entity every tick, so a troop moved by something outside
   * this system - a knockback, a pull, an ability - is picked up rather than snapped back. The
   * previous-position copy is refreshed from it at the same moment, so it holds where the entity
   * stood at the head of this tick.
   *
   * <p>The state of a managed troop belongs to this system and is left alone. The state of a troop
   * it does not manage is mirrored from the simulator, so that it reads as deploying while its own
   * deploy timer runs and as moving afterwards; a managed troop's candidates then see a sensible
   * state on it.
   */
  private void syncView(GridEntity view, Entity entity) {
    // The previous-position copy is refreshed from the current position at the head of every tick,
    // before anything has moved, so a pass that reads it during the tick sees where the entity
    // stood when the tick began.
    view.setPrevX(entity.getPosition().getX());
    view.setPrevY(entity.getPosition().getY());
    view.setPrevZ(view.getZ());
    view.setX(entity.getPosition().getX());
    view.setY(entity.getPosition().getY());
    view.setAlive(entity.isAlive());
    view.setAir(entity.getMovementType() == MovementType.AIR);
    view.setZTotal(view.isAir() ? AIR_HEIGHT : 0);
    view.setCollisionRadius(entity.getCollisionRadius());
    if (entity instanceof Troop troop && !manages(troop)) {
      boolean deploying = troop.isDeploying();
      view.setState(deploying ? GridEntityState.DEPLOYING : GridEntityState.MOVING);
      view.setDeployCountdown(deploying ? Math.round(troop.getDeployTimer() * 1000f) : 0);
    }
    TargetView target = targetViews.get(entity.getId());
    if (target != null && entity.getHealth() != null) {
      target.setHitPoints(entity.getHealth().getCurrent());
    }
  }

  /** Registers every view this troop's chain has not seen yet. */
  private void syncRegistrations(GridUnitState unit) {
    SelectionChain chain = unit.selection();
    for (GridEntity view : orderedViews) {
      if (chain.view(view) == null) {
        chain.register(targetViews.get((long) view.getId()));
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Per-troop state
  // -----------------------------------------------------------------------------------------

  /**
   * Creates the grid state of a freshly deployed troop: the deploying state with its deploy
   * countdown, a facing that points down the arena for the blue side and up it for the red side,
   * the placement position, and the road id the deploy position sits nearest to.
   *
   * <p>The deployment's own lane flag is not modelled: the lane is the plain nearest-road answer,
   * which is what an ordinary deploy produces on the standard arena.
   */
  private GridUnitState createUnitState(Troop troop, GridEntity view) {
    MovementState movement = MovementState.forSide(view.getSide(), view.getX(), view.getY());
    TargetingState targeting = new TargetingState();
    targeting.setOwner(view);
    targeting.setConfig(configFor(troop));
    targeting.setMovementComponentActive(true);
    SelectionChain chain = new SelectionChain(index, targeting, tileMap.height());
    registerEnemyTowers(chain, troop.getTeam());
    GridUnitState unit =
        new GridUnitState(
            view,
            movement,
            targeting,
            new StateTimers(),
            MovementConfig.forGroundUnit(),
            SpeedConfig.forGroundUnit(rawSpeed(troop.getMovement())),
            StateVisitConfig.forGroundUnit(deployTimeMs(troop)),
            chain,
            targetViews.get(troop.getId()));
    // Every state change of the troop, and the route preparation that storing a reference asks
    // for, goes through the troop's own setter.
    GridStateSetter setter =
        new GridStateSetter(view, movement, targeting, () -> movementChain(unit));
    chain.setStateSetter(setter);
    chain.getOutcome().setRoutePreparer(setter::prepareRoute);
    return unit;
  }

  /** The movement pass's answers for one troop as it stands now, reference included. */
  private GridMovementQueries movementQueries(GridUnitState unit) {
    return new GridMovementQueries(unit, grid, costs, unitStates::get);
  }

  /** A movement chain over the troop's current reference, for one visit or one preparation. */
  private MovementChain movementChain(GridUnitState unit) {
    return movementChain(unit, movementQueries(unit));
  }

  private MovementChain movementChain(GridUnitState unit, GridMovementQueries queries) {
    return new MovementChain(
        unit.movement(),
        unit.entity(),
        grid,
        unit.movementConfig(),
        movementGlobals,
        queries.reference(),
        neighbourQuery,
        queries);
  }

  /**
   * Registers the opposing side's crown towers as the default target candidates, in placement order
   * - king first, then the princess tower with the lower position along the arena's width, then the
   * other - and seeds the selection with the king tower.
   */
  private void registerEnemyTowers(SelectionChain chain, Team team) {
    Team enemy = team == Team.BLUE ? Team.RED : Team.BLUE;
    Tower crown = gameState.getCrownTower(enemy);
    if (crown != null) {
      TargetView view = targetViews.get(crown.getId());
      if (view != null) {
        chain.registerTower(view);
        chain.setSeed(view);
      }
    }
    List<Tower> princesses = new ArrayList<>(gameState.getPrincessTowers(enemy));
    princesses.sort(Comparator.comparingInt(tower -> tower.getPosition().getX()));
    for (Tower princess : princesses) {
      TargetView view = targetViews.get(princess.getId());
      if (view != null) {
        chain.registerTower(view);
      }
    }
  }

  /**
   * The targeting columns of one entity. Distances are game units and times are milliseconds, both
   * as the simulator already stores them. A building or a tower is described by the tower factory,
   * which is the same set of columns with the building flag raised.
   *
   * <p>Every targeting column the simulator's combat component carries is copied across. The
   * columns the targeting pass knows about but the simulator does not store anywhere keep the
   * standard-character default and are listed here so it is plain which behaviour is not reachable
   * yet: the burst columns, the attack sequence columns, the dash columns, the special-attack
   * columns, {@code targetLowestHp}, {@code doNotTargetTowers}, {@code targetOnlyTowers}, {@code
   * targetOnlyKingTower}, {@code deprioritizeTargetsWithBuff}, {@code loadFirstHit}, {@code
   * lifeTime} (the simulator keeps only the countdown, not the column), {@code sightClip} and
   * {@code sightClipSide} (both at the standard character values), and {@code suckElixirSpeed}.
   */
  private TargetingConfig configFor(Entity entity) {
    Combat combat = entity.getCombat();
    int range = combat == null ? 0 : combat.getRange();
    int sightRange = combat == null ? 0 : combat.getSightRange();
    int hitSpeed = combat == null ? 0 : Math.round(combat.getAttackCooldown() * 1000f);
    int loadTime = combat == null ? 0 : Math.round(combat.getLoadTime() * 1000f);
    int radius = entity.getCollisionRadius();
    String key = entity.getName() == null ? "unit" : entity.getName();
    if (entity.getMovementType() == MovementType.BUILDING) {
      boolean summoner = entity instanceof Tower tower && tower.isPrincessTower();
      return TargetingConfig.tower(key, range, sightRange, radius, hitSpeed, loadTime, summoner);
    }
    TargetType targetType = combat == null ? TargetType.GROUND : combat.getTargetType();
    boolean ground = targetType == TargetType.GROUND || targetType == TargetType.ALL;
    boolean air = targetType == TargetType.AIR || targetType == TargetType.ALL;
    TargetingConfig base =
        TargetingConfig.forUnit(range, sightRange, radius, hitSpeed, loadTime, ground, air);
    if (combat == null) {
      return base.toBuilder().configKey(key).build();
    }
    String ignoredBuff = combat.getIgnoreTargetsWithBuff();
    return base.toBuilder()
        .configKey(key)
        .minimumRange(combat.getMinimumRange())
        .targetOnlyBuildings(combat.isTargetOnlyBuildings())
        .targetOnlyTroops(combat.isTargetOnlyTroops())
        .hasProjectile(combat.getProjectileStats() != null)
        .multipleTargets(combat.getMultipleTargets())
        .attackDashTime(Math.round(combat.getAttackDashTime() * 1000f))
        .ignoreTargetsWithBuff(ignoredBuff != null && !ignoredBuff.isBlank())
        .build();
  }

  /**
   * The unit's speed column, in game units per tick.
   *
   * <p>A component built from a unit's stats carries the column itself, and that is what is used. A
   * component built by hand - which only test fixtures do - leaves the column at zero; for those
   * the column is recovered from the component's game-units-per-second speed by inverting the fixed
   * factor the card data is loaded with, so such a fixture still moves at the right rate.
   *
   * <p>The base speed is used and not the effective one: status effects do not feed the grid speed
   * budget yet.
   */
  private static int rawSpeed(Movement movement) {
    if (movement == null) {
      return 0;
    }
    if (movement.getRawSpeed() != 0) {
      return movement.getRawSpeed();
    }
    return Math.round(
        movement.getSpeed() * GameUnits.RAW_SPEED_PER_TILE_PER_SECOND / GameUnits.UNITS_PER_TILE);
  }

  /** The troop's deploy time in milliseconds, as the grid deploy countdown starts at. */
  private static int deployTimeMs(Entity entity) {
    if (entity instanceof Troop troop) {
      return Math.round(troop.getDeployTime() * 1000f);
    }
    return 0;
  }

  /** The state-visit answers for an ordinary unit that may follow a route. */
  private static StateQueries stateQueries(GridEntity entity) {
    return StateQueries.forUnitWithRoute(entity.getSide() & 1);
  }

  /** Side 0 is the blue player, at the low end of the arena; side 1 is the red player. */
  private static int side(Team team) {
    return team == Team.BLUE ? 0 : 1;
  }

  // -----------------------------------------------------------------------------------------
  // Writing back into the simulator
  // -----------------------------------------------------------------------------------------

  /**
   * Copies the chosen reference into the troop's combat component.
   *
   * <p>The target is written only when it differs from the one already held, because the setter
   * resets the attack wind-up and the attack state machine. The lock is raised on every tick the
   * troop stands in the attacking state and is never lowered here.
   */
  private void writeCombat(Troop troop, GridUnitState unit) {
    Combat combat = troop.getCombat();
    if (combat == null) {
      return;
    }
    TargetView reference = unit.targeting().getReference();
    Entity target = reference == null ? null : simulatorEntities.get((long) reference.id());
    if (combat.getCurrentTarget() != target) {
      combat.setCurrentTarget(target);
    }
    if (unit.entity().getState() == GridEntityState.ATTACKING && target != null) {
      combat.setTargetLocked(true);
    }
  }
}
