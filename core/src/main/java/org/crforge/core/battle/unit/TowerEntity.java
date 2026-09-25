package org.crforge.core.battle.unit;

import java.util.ArrayList;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.GameTags;
import org.crforge.core.battle.action.PresentationAction;
import org.crforge.core.battle.action.WaitToActivate;
import org.crforge.core.battle.action.WithDuration;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridStateSetter;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.ResumeHelper;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingVisit;

/**
 * A crown tower: a building that stands still, occludes the routing grid under its footprint, is
 * what a unit with nothing else to attack walks towards, and shoots at what comes into its range.
 *
 * <p>A tower carries the same targeting component as a troop, in the same slot, and no movement
 * component, so it never leaves the standing state except to attack. With nothing in range it holds
 * the opposing side's tower its default selection gives as its reference, out of range, and selects
 * again every tick; once a unit comes into range it locks on, fires its projectile on the attack
 * ticks, and returns to standing when the reference is gone. Its post-hook is the entity state
 * visit, which steps its elapsed time from its first tick, and at its end the combat gate, which
 * switches the targeting component off while the tower is inactive.
 *
 * <p>A king tower sleeps until its side loses a princess tower or it loses hit points itself. Its
 * placement queues a wait that sets the inactive tag; the first tick's first pending pass starts
 * it, after that tick's tags were folded, so the king is visited on its first two ticks before the
 * gate switches it off. The run pass that first sees the condition ends the wait, which queues a
 * 3300 ms activating run that the same tick's second pending pass starts. That run keeps the tag
 * that holds the component off until the run pass after it finishes removes it, so the king's first
 * visit falls seventy ticks after the tick that saw the condition.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: a tower occludes routing from its collision radius, takes part in pushes and"
            + " steering as a static neighbour with a mass of 0, is a default target, answers as a crown tower whether king or princess"
            + " tower and as the tower slot only when king, and stands at its hit points at its"
            + " level; it carries the targeting component in slot 0 and no movement component,"
            + " the building branches of the targeting visit, the state visit as its post-hook"
            + " with the combat gate at its end, and a king tower asleep from creation, visited"
            + " on its first two ticks, the wait its placement queues and its condition, the"
            + " activating run that follows and the tags both set, which the pre-hook folds in and"
            + " the gate reads. Supplied, not settled: the towers scale as Common, and the"
            + " enabling side of the gate answers for a standing, living tower. Not modelled: the"
            + " rest of the king's own state visit, and the starting group around its wait, whose"
            + " own body is empty.")
public class TowerEntity extends WorldEntity {

  /** Slot of the targeting component, the same slot a troop's is in. */
  public static final int TARGETING_SLOT = 0;

  /** How long a king tower takes to wake once its wait has ended, in milliseconds. */
  public static final int ACTIVATION_MS = 3300;

  /** The king's wake-up condition, compiled once; null for a princess tower. */
  private final Expression activationExpression;

  /** Applies every state change the tower asks for; a tower has no route to act on. */
  private final GridStateSetter setter;

  /** The countdowns the state visit owns. */
  private final StateTimers timers = new StateTimers();

  /** The tower's state-visit columns: no deploy time, and none of the other columns set. */
  private final StateVisitConfig stateConfig = StateVisitConfig.forGroundUnit(0);

  /** True for a tower placed to stand passive: its targeting component never runs. */
  private boolean holdingFire;

  /** The king's scheduled actions; null for a princess tower, which has none. */
  private final ActionHolder actionHolder;

  /** The activating run the king's wait schedules when it ends; null for a princess tower. */
  private final BattleAction activating;

  /** The effect shown alongside the activating run; null for a princess tower. */
  private final BattleAction activationEffect;

  /** The tags folded in at the last pre-hook. */
  private long tags;

  /**
   * @param world the battle's shared arena state, whose arena assigns the tower its lane from the
   *     road nearest to it
   * @param data the tower's published columns
   * @param name the tower's unique name within the battle
   * @param side the side that owns the tower
   * @param x position in game units
   * @param y position in game units
   * @param level the tower's level, counted from 1
   */
  public TowerEntity(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level) {
    super(
        world,
        data,
        createView(world.getTileMap(), data, name, side, x, y),
        targetingConfig(data),
        level);
    this.setter = new GridStateSetter(getView(), null, getTargeting(), () -> null);
    SelectionChain selection = getSelection();
    selection.setStateSetter(setter);
    selection.getOutcome().setRoutePreparer(setter::prepareRoute);
    // A building with no reference resets its attack only when it has hit points at the first
    // level; every tower does.
    selection.setBuildingKeepsAttacking(data.hitpoints() != 0);
    attach(new TargetingComponent());

    if (data.king()) {
      this.activationExpression =
          ExpressionCompiler.compile(
              ACTIVATION_CONDITION, new BattleExpressionEnvironment(this, world));
      this.actionHolder = new ActionHolder();
      this.activationEffect = new PresentationAction("KingTowerActivationEffect");
      this.activating =
          new WithDuration(
              "WaitForKingTowerActivation.OnActivateAction",
              ACTIVATION_MS,
              GameTags.ACTIVATING,
              activationEffect);
      actionHolder.setListener(new ActivationListener());
      // The placement queues the wait; with no pending pass running it waits for the first one.
      actionHolder.schedule(
          new WaitToActivate(
              "WaitForKingTowerActivation",
              this::activationCondition,
              activating,
              GameTags.INACTIVE),
          0);
    } else {
      this.activationExpression = null;
      this.actionHolder = null;
      this.activating = null;
      this.activationEffect = null;
    }
  }

  /**
   * What ends the king's wait, as the data writes it: the king has lost hit points, or its side has
   * fewer than two princess towers left, or the same of a co-op side. Every part is evaluated, as
   * the language never skips the right side of an or.
   */
  static final String ACTIVATION_CONDITION =
      "king_tower_damaged() || coop_king_tower_damaged() || tower_destroyed()"
          + " || coop_tower_destroyed()";

  /**
   * The wait's condition, evaluated by the wait's step in every run pass. When it holds, observers
   * are told which part did.
   */
  private boolean activationCondition() {
    BattleExpressionEnvironment environment = new BattleExpressionEnvironment(this, world);
    if (ExpressionEvaluator.evaluate(activationExpression, environment) == 0) {
      return false;
    }
    boolean damaged = environment.call(BattleFunctions.id("king_tower_damaged"), new int[0]) != 0;
    boolean towerDestroyed =
        environment.call(BattleFunctions.id("tower_destroyed"), new int[0]) != 0;
    world.activation(
        this, new ActivationEvent(ActivationEvent.Kind.CONDITION, 0, damaged, towerDestroyed));
    return true;
  }

  /** Turns the king's action steps into the activation events observers are told. */
  private final class ActivationListener implements ActionHolder.Listener {

    @Override
    public void started(BattleAction action, int phase) {
      if (action == activating) {
        world.activation(
            TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.ACTIVATING_STARTED, phase));
      } else if (action == activationEffect) {
        world.activation(TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.EFFECT, phase));
      }
    }

    @Override
    public void finished(ActionInstance instance) {
      if (instance.getAction() == activating) {
        world.activation(
            TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.ACTIVATING_FINISHED, 0));
      }
    }

    @Override
    public void removed(ActionInstance instance) {
      if (instance.getAction() == activating) {
        world.activation(
            TowerEntity.this, ActivationEvent.of(ActivationEvent.Kind.ACTIVATING_REMOVED, 0));
      }
    }
  }

  /**
   * The tower's view at placement. Like every character it is given the lane of the road nearest to
   * its position, which the default selection compares with a unit's own lane.
   */
  private static GridEntity createView(
      TileMap tileMap, UnitData data, String name, int side, int x, int y) {
    GridEntity view = new GridEntity();
    view.setName(name);
    view.setSide(side);
    view.setLane(
        LaneAssignment.lane(
            tileMap.width(), tileMap.height(), tileMap.width(), x, y, -1, 0, tileMap::bits));
    view.setCollisionRadius(data.collisionRadius());
    view.setMass(data.mass());
    view.setBuilding(true);
    view.setOccludes(true);
    view.setMovementComponent(false);
    view.setMovementActive(false);
    // Both tower kinds are crown towers: noticed from farther away, ordered last by the index and
    // dealt the crown-tower damage. Only the king tower fills its side's tower slot.
    view.setCrownTower(data.king() || data.summonerTower());
    view.setKingCandidate(data.king() ? 1 : 0);
    view.setTargetable(1);
    view.setX(x);
    view.setY(y);
    return view;
  }

  private static TargetingConfig targetingConfig(UnitData data) {
    return TargetingConfig.tower(
            data.name(),
            data.range(),
            data.sightRange(),
            data.collisionRadius(),
            data.hitSpeedMs(),
            data.loadTimeMs(),
            data.summonerTower())
        .toBuilder()
        .hasProjectile(data.hasProjectile())
        .build();
  }

  /**
   * Keeps the tower passive for the rest of the battle: its targeting component is switched off and
   * the gate never switches it back on. The reference runs made without the towers fighting are
   * played this way.
   */
  public void holdFire() {
    holdingFire = true;
    setActive(TARGETING_SLOT, false);
  }

  /** Whether the tower was placed to stand passive for the whole battle. */
  public boolean isHoldingFire() {
    return holdingFire;
  }

  /**
   * Whether the tags folded in at the last pre-hook keep the targeting component off: a sleeping or
   * waking king. A princess tower never is.
   */
  public boolean isInactive() {
    return (tags & GameTags.KEEPS_TARGETING_OFF) != 0;
  }

  @Override
  public EntityActions actions() {
    return actionHolder == null ? EntityActions.NONE : actionHolder;
  }

  /** The tag fold: the entity's tags are the tags of every action instance it lists. */
  @Override
  protected void preHook() {
    tags = actionHolder == null ? 0 : actionHolder.tags();
  }

  private StateQueries stateQueries() {
    // A tower has no movement component, so it can never be given a route: a resume stands it.
    return StateQueries.forUnitWithRoute(side() & 1).withMayHoldRoute(false);
  }

  /**
   * The entity state visit, which for a standing tower steps its elapsed time and nothing else, and
   * at its end the combat gate: the targeting component runs while the tower is not inactive.
   */
  @Override
  protected void postHook() {
    EntityStateVisit.stateVisit(
        getView(),
        timers,
        null,
        stateConfig,
        StateVisitGlobals.standard(),
        stateQueries(),
        new ArrayList<>(),
        setter);
    setActive(TARGETING_SLOT, !holdingFire && !isInactive());
  }

  /** Chooses, keeps or drops the tower's target and decides whether it fires this tick. */
  private final class TargetingComponent implements BattleComponent {

    @Override
    public int index() {
      return TARGETING_SLOT;
    }

    @Override
    public void visit() {
      SelectionChain selection = getSelection();
      selection.beginTick();
      TargetingVisit.targetingVisit(
          getTargeting(), getView(), null, selection, selection.getOutcome());
      if (selection.getOutcome().isResumeRequested()) {
        ResumeHelper.resume(getView(), stateConfig, stateQueries(), new ArrayList<>(), setter);
      }
    }
  }
}
