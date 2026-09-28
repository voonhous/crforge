package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import org.crforge.core.battle.BattleComponent;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.FriendCollecting;
import org.crforge.core.battle.action.GameTags;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.GridMovementQueries;
import org.crforge.core.pathfinding.GridStateSetter;
import org.crforge.core.pathfinding.GridUnitState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.grid.CellTests;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.AttachedParent;
import org.crforge.core.pathfinding.move.DashStart;
import org.crforge.core.pathfinding.move.MovementChain;
import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementRequests;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.MovementVisit;
import org.crforge.core.pathfinding.move.PushbackQueries;
import org.crforge.core.pathfinding.move.PushbackRequest;
import org.crforge.core.pathfinding.move.SpeedBudget;
import org.crforge.core.pathfinding.move.SpeedConfig;
import org.crforge.core.pathfinding.state.EntityStateVisit;
import org.crforge.core.pathfinding.state.ResumeHelper;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.state.StateVisitGlobals;
import org.crforge.core.pathfinding.target.HitQueries;
import org.crforge.core.pathfinding.target.RangeTest;
import org.crforge.core.pathfinding.target.ReferenceSetter;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.TargetingVisit;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * A walking ground unit: a targeting component in slot 0, a movement component in slot 1 and the
 * entity state visit as its post-hook. A building is one too, without the movement component: it
 * stands where it is placed, and other units treat it as the obstacle a tower is. An air unit is
 * one too, created at its row's flying height, which it keeps: the same code with other layer
 * answers, so it routes to one node, crosses water, and meets in the contact passes only units on
 * its side of height 0.
 *
 * <p>Because the holder runs one whole-list pass per slot, every character has chosen its target
 * for the tick before any character moves, and every character has moved before any character's
 * state visit runs. A character is created deploying and takes no decisions until its deploy
 * countdown, stepped by the state visit, runs out: its targeting component is not visited, and its
 * movement component is, but asks for no route and gets no speed, so only a push can move it.
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
            + " deploy countdown stepping 50 ms per state visit, every state change and route"
            + " preparation going through the unit's own setter, each hit recorded on the"
            + " attacker and landing on its target directly or as the projectiles it launches in"
            + " the same tick, the hit points and damage at the character's level, the removal"
            + " notice dropping a reference to an entity that left and starting the target-lost"
            + " countdown, and the resume that follows; while deploying, no targeting visit, and a"
            + " movement visit that asks for no route and no speed, so only a push moves it; while"
            + " waiting its turn to deploy, neither visit, the state visit counting the wait down"
            + " into the deploying state, which starts the deploy countdown; its death switching"
            + " its movement component off for the rest of the tick; the lane its"
            + " placement works out; a spawned child walking at once or set deploying, its"
            + " registration visit inside the spawning pass and its first-tick immunity; a"
            + " building standing without a movement component while it deploys, held by the"
            + " Tombstone killed while deploying; a row swap taking the new row's radius, mass, speed"
            + " and targeting columns at once, the reference cleared raw and a kept target stored"
            + " again through the setter, held by golemite_convert; the starting-attack row queued"
            + " on the character at the first attack step, at each hit and on a new reference in"
            + " range, running in its phase-2 pass, and the attack sequence entry at the index"
            + " launching in place of the row's projectile, held by the evolved Archer's runs; a"
            + " spawned child without a speed standing, held by area_effect_death; a death spawn"
            + " child put on the dying unit and flown back to its ring point in steps of 250, held"
            + " by golem_death_pushback; and an object without hit points running its death slot"
            + " as its deploy ends and leaving at the next cleanup, held by giant_skeleton_bomb; a"
            + " building's deploy ending in the standing state through the resume, its targeting"
            + " from the next tick and its attacks as a tower's, the minimum range with the"
            + " collision radius added, the lifetime decay in the third component pass from the"
            + " tick after the deploy and its death with the death slot and no death handler, and"
            + " the live spawner from the deploy end - its timer, its waves and its children in"
            + " front - held by cannon_knight, tombstone_life, goblin_hut_life and mortar_knight;"
            + " a walking unit's own spawner, stepping from its deploy end whether it walks or"
            + " attacks, its waves on the ring turned by its angle shift and facing, held by"
            + " witch_left_lane and night_witch; a card play's riders, made as the unit starts"
            + " deploying and queued ahead of it, placed around it a tick behind, untouchable,"
            + " untargetable and out of collisions, targeting and attacking on their own, and let"
            + " go in the cleanup that removes their parent, their death slot run and themselves"
            + " removed in that cleanup, held by goblin_giant_tower;"
            + " the combat gate at the state visit's tail, which drops a dead character's"
            + " reference and switches its targeting off, held by every run's death tick; its"
            + " buffs scaling its speed budget and its attack timer, held by rage_knight,"
            + " zap_knight and poison_knight_tower, and the follower's step, the deploy step and"
            + " the spawner's rate, which no run holds; an air unit created at its flying height"
            + " and keeping it, its layer answered from it - one route node, the flat endpoint"
            + " rank, flight over water, contact only with units on its side of height 0, targets"
            + " by the validator's air and ground pairing, projectiles from its height - held by"
            + " minions_left, minion_musketeer, balloon_tower, balloons_cross, lava_hound_river"
            + " and baby_dragon_left; a charge built by the steps it walks, its doubled speed, its"
            + " strike-now byte landing the first hit at once and that hit's special damage, held"
            + " by prince_tower and dark_prince_tower; a river jump over the water its route"
            + " crosses, from the node before the water to the first land cell beyond it at its"
            + " jump speed, held by hog_river; a dash, its wind-up standing it still inside its"
            + " ring, its start short of its reference by both radii, its flight at its jump speed"
            + " with its stop in range or its constant time and height, its landing hit on its"
            + " reference or over its radius with a push, its landing hold, its immunity while it"
            + " dashes and after, and the resume when it loses its reference, held by bandit_knight"
            + " and mega_knight_group; a card play's rider that targets troops only, its buff"
            + " priority fed, held by ram_rider_tower; an elixir collector's payout to its king,"
            + " held at the cap and paid once the king can take it, and an Elixir Golem's death"
            + " paying the killing side, held by match_elixir_sources (the carry a payout leaves"
            + " when the spawn step does not divide the generation time is held by no run); the"
            + " building's targeting and attack there rest on the verified translations, not"
            + " a native run. The Kamikaze hit's end, the unit's kill of itself after its hit, is"
            + " held by the Battle Ram's, Fire Spirits', Wall Breakers' and Ice Spirits' runs. Held by"
            + " no run: a spawner's start time other than 0, a top-side"
            + " building's in-front point, a Kamikaze end after a cancelled hit, and the facing a"
            + " death-spawned child takes with a deploy time. Refused: the columns its row sets that the battle does"
            + " not model (a shield's push or action as it breaks, hiding, a buff at a share of its"
            + " hit points, hovering, direct"
            + " paths, a completed charge's action, a chained dash, a dash's contact damage,"
            + " fixed distance, area effect or closing action, a limit on the elixir it makes, a"
            + " spawner's launches, second and third characters, limit, push and"
            + " deploy for its children, a Kamikaze drain over a time), a charge on a unit that"
            + " fires, a Kamikaze hit's end on a unit carrying a death-spawn buff or a shield, a"
            + " lifetime's death"
            + " with a death action, an attack sequence whose mode moves the index itself or whose"
            + " entries set more than a projectile and a damage, an action run as it attacks, and"
            + " a row that attaches riders placed directly, spawned or waiting its turn, a buff on a"
            + " parent or a rider, a rider whose parent may not attack, and"
            + " a swap that builds or frees the movement component or reaches a lifetime, a"
            + " spawner, a building or a flying row, a champion, another deploy time, a charge,"
            + " a river jump or a dash; a dash's landing on a cell it may not stand on; a shot"
            + " whose projectile sets a column its flight or impact does not model; and a buff a"
            + " character's targeting passes over, applied to anyone."
            + " Not modelled yet: the registration visit of a unit a card play creates, which"
            + " meets an empty index, the"
            + " hit-points visit's dedupe expiry, and the columns its data does not"
            + " carry: the stop time after an attack and the ones that restrict what a unit may"
            + " target beyond buildings only, which it carries.")
public class CharacterEntity extends WorldEntity {

  /** Slot of the targeting component. */
  public static final int TARGETING_SLOT = 0;

  /** Slot of the movement component. */
  public static final int MOVEMENT_SLOT = 1;

  /** Slot of the hit-points component, whose visit runs the lifetime decay. */
  public static final int HIT_POINTS_SLOT = 2;

  /** How far beyond its attack range a dash's single landing hit still reaches its reference. */
  private static final int DASH_HIT_EXTENSION = 500;

  /** Where a landing hold starts; the state visit adds 50 a visit until the landing time. */
  private static final int LANDING_HOLD_START_MS = 50;

  /** The follower's time step before the buffs scale it. */
  private static final int FOLLOWER_STEP = 100;

  /** The deploy countdown's step before the buffs scale it, for a row whose speed scales it. */
  private static final int DEPLOY_STEP_MS = 50;

  /** The working state of the character's two components and its state visit. */
  @Getter private GridUnitState unit;

  /** What a pushback request asks of the character: whether its row ignores pushback. */
  private final PushbackQueries pushbackQueries = () -> getData().ignorePushback();

  /** Applies every state change the character asks for, with the actions the change carries. */
  private final GridStateSetter setter;

  /**
   * The movement budget the last movement visit asked for, in game units per tick; zero when it
   * asked for none, as a standing or deploying character's visit does.
   */
  @Getter private int speedBudget;

  /** The children linked into this character's group, newest first. */
  private final List<CharacterEntity> group = new ArrayList<>();

  /** The character whose group this one is linked into, or null for none. */
  private CharacterEntity groupSource;

  /**
   * The spawner's timer: what is left before its next firing, in milliseconds, from its row's start
   * time at placement.
   */
  private int spawnTimer;

  /** An elixir collector's timer: what it has counted toward its next payout, in milliseconds. */
  private int collectorTimerMs;

  /** How many children of the current wave the spawner has made. */
  private int spawnWaveMade;

  /** The character this one rides on, or null while it rides on none. */
  @Getter private CharacterEntity parent;

  /** Its angle on its parent's ring as it was made, which gives its share of the arc it sits on. */
  private int attachAngle;

  /** The characters riding on this one, in the order they were made. */
  private final List<CharacterEntity> riders = new ArrayList<>();

  /** True once it has been let go by its parent, which removes it at the same cleanup. */
  private boolean released;

  /** True when its charge was complete at the end of its last movement visit. */
  private boolean charged;

  /**
   * The character's answers to its movement pass's requests: a state change goes to its state
   * setter at once, and a completed charge is told to the world's observers. The action a completed
   * charge runs is refused with its row.
   */
  private final MovementRequests movementRequests =
      new MovementRequests() {
        @Override
        public void requestState(int state) {
          int from = getView().getState();
          setter.setState(getView(), state);
          world.movementStateRequested(CharacterEntity.this, from, getView().getState());
        }

        @Override
        public void chargeCompleted() {
          world.chargeCompleted(CharacterEntity.this, unit.movement().getChargeProgress());
        }

        @Override
        public void dashLanded() {
          landDash();
        }
      };

  /**
   * The character's dash, for a row with a dash cooldown: its range check and its start. A buff
   * that pulls, which would hold the dash back, is refused with its row.
   */
  private final SelectionChain.Dasher dasher =
      new SelectionChain.Dasher() {
        @Override
        public boolean inDashRange(TargetView reference) {
          UnitData data = getData();
          if (unit.targeting().getDashWindupMs() > 0) {
            return true;
          }
          if (SpeedBudget.speedModifier(getBuffs().speedPercents(), data.speed()) < 1) {
            return false;
          }
          return RangeTest.rangeTest(
              reference,
              getView().getX(),
              getView().getY(),
              data.dashMaxRange(),
              data.collisionRadius() + data.dashMinRange(),
              false);
        }

        @Override
        public void startDash(int x, int y, int radius) {
          GridEntity view = getView();
          int fromX = view.getX();
          int fromY = view.getY();
          TargetView reference = unit.targeting().getReference();
          DashStart.start(
              view,
              isActive(MOVEMENT_SLOT) ? unit.movement() : null,
              unit.movementConfig(),
              x,
              y,
              radius,
              1,
              world.getGrid().getWidth(),
              world.getGrid().getHeight(),
              setter);
          world.dashStarted(CharacterEntity.this, reference, fromX, fromY, x, y);
        }
      };

  /**
   * Creates a character at its deploy position, deploying.
   *
   * @param world the battle's shared arena state
   * @param data the character's published columns; only ground units and buildings are supported
   * @param name the character's unique name within the battle
   * @param side the side that owns the character
   * @param x deploy position in game units
   * @param y deploy position in game units
   * @param level the character's level, counted from 1
   */
  public CharacterEntity(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level) {
    this(world, data, name, side, x, y, level, -1, -1);
  }

  /**
   * Creates a character as a card play places it: in the lane the play gives it, and either
   * deploying or waiting its turn to deploy.
   *
   * @param world the battle's shared arena state
   * @param data the character's published columns; only ground units and buildings are supported
   * @param name the character's unique name within the battle
   * @param side the side that owns the character
   * @param x position in game units
   * @param y position in game units
   * @param level the character's level, counted from 1
   * @param lane the lane the play gives it, or -1 for the lane of the road nearest to it
   * @param waitMs how long it waits before it starts deploying, or -1 to deploy at once
   */
  public CharacterEntity(
      BattleWorld world,
      UnitData data,
      String name,
      int side,
      int x,
      int y,
      int level,
      int lane,
      int waitMs) {
    super(
        world,
        data,
        createView(world.getTileMap(), data, name, side, x, y),
        targetingConfig(data),
        level);
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          data.name() + " sets columns the battle does not model: " + data.unmodelledColumns());
    }
    refuseAttack(data);
    // The level setter copies the spawner's start time into its timer.
    this.spawnTimer = data.spawnStartTimeMs();

    GridEntity view = getView();
    TargetingState targeting = getTargeting();
    targeting.setMovementComponentActive(!data.building());
    this.unit =
        new GridUnitState(
            view,
            MovementState.forSide(side, x, y),
            targeting,
            new StateTimers(),
            // The movement config's flying height is read only for direct paths, which are
            // refused; its stop and wait make the follower walk in bursts, its charge range
            // builds the charge, its jump leaps the river and gives a dash its height, and its
            // constant dash time times a dash.
            MovementConfig.forGroundUnit(data.stopMovementAfterMs(), data.waitMs())
                .withCharge(data.chargeRange())
                .withJump(data.jumpEnabled(), data.jumpHeight())
                .withDashConstantTime(data.dashConstantTimeMs())
                .withSpawnPathfindSpeed(data.spawnPathfindSpeed())
                .withEntersWaterWhileSpawnPathfinding(data.spawnPathfindMorph() != null),
            SpeedConfig.forGroundUnit(data.speed())
                .withChargeMultiplier(data.chargeSpeedMultiplier())
                .withJumpSpeed(data.jumpSpeed())
                .withSpawnPathfindSpeed(data.spawnPathfindSpeed()),
            StateVisitConfig.forGroundUnit(data.deployTimeMs())
                .withDash(data.dashLandingTimeMs(), data.dashImmuneToDamageTimeMs())
                .withSpawnPathfindMorph(data.spawnPathfindMorph() != null),
            getSelection(),
            getTargetView());
    this.setter =
        new GridStateSetter(
            view,
            unit.movement(),
            targeting,
            this::movementChain,
            data.deployTimeMs(),
            unit.movementConfig());
    // A unit with an ability casts through its setter, which seeds the cast's countdowns and ends a
    // change into or out of the cast with the combat gate.
    if (data.ability() != null) {
      setter.setCasting(
          new GridStateSetter.Casting(
              unit.timers(),
              data.ability().castTimeMs(),
              data.ability().triggerDelayMs(),
              false,
              this::stateTailGate));
    }
    // A row with an area object makes it each time it enters the deploying state through its
    // setter.
    if (data.spawnAreaObject() != null) {
      setter.setDeployingEntry(() -> world.spawnAreaObject(this));
    }
    // The movement component starts tracking a charge for a row with a charge range.
    if (data.chargeRange() != 0) {
      unit.movement().setChargeProgress(0);
    }
    if (lane >= 0) {
      view.setLane(lane);
    }
    if (waitMs >= 0) {
      // Waiting its turn: the elapsed-time field holds the wait, which the state visit counts
      // down; at zero the unit enters the deploying state, whose countdown the setter seeds.
      // Its movement component is switched off until then.
      view.setState(GridEntityState.WAITING_TO_DEPLOY);
      view.setDeployCountdown(0);
      view.setDelay(waitMs);
      view.setMovementActive(false);
    }
    checkArgument(
        !data.building() || waitMs < 0,
        () -> data.name() + " is a building, whose wait to deploy is not modelled");
    unit.selection().setStateSetter(setter);
    if (data.onStartingAttackAction() != null) {
      // The visit asks for it on the first attack step and on each step that completes a hit, the
      // setter on a new reference already in range; either way it is queued on the character, the
      // character as its cause, and runs in its phase-2 pending pass of the tick.
      unit.selection().setOnStartingAttack(this::startingAttack);
    }
    unit.selection().getOutcome().setRoutePreparer(setter::prepareRoute);
    if (data.dashCooldown() > 0) {
      unit.selection().setDasher(dasher);
    }

    attach(new TargetingComponent());
    if (!data.building()) {
      attach(new MovementComponent());
    }
    if (getHitPoints() != null) {
      attach(new HitPointsComponent());
    }
  }

  /**
   * Creates a character as a spawn creates it: in the lane of its own position and, as the level
   * setter leaves a unit with a speed, walking at once with every component on. The spawner then
   * sets it deploying when its row asks.
   *
   * @param world the battle's shared arena state
   * @param data the child's published columns; only ground units are supported
   * @param name the child's unique name within the battle
   * @param side the side that owns the child, its source's
   * @param x position in game units, already inside the arena
   * @param y position in game units, already inside the arena
   * @param level the child's level, counted from 1
   */
  static CharacterEntity spawned(
      BattleWorld world, UnitData data, String name, int side, int x, int y, int level) {
    if (data.spawnAttach()) {
      throw new UnsupportedOperationException(
          data.name() + " is spawned and would make its riders as it deploys, which is not held");
    }
    CharacterEntity child = new CharacterEntity(world, data, name, side, x, y, level);
    // The level setter leaves a unit with a speed walking and one without standing.
    child.getView().setState(data.speed() >= 1 ? GridEntityState.MOVING : GridEntityState.STANDING);
    child.getView().setDeployCountdown(0);
    return child;
  }

  /**
   * Puts a death spawn's child on the dying unit's point and aims it back at its own point on the
   * ring, as a death spawn that pushes its children does: its movement component flies it there in
   * steps of 250, one per movement visit, for as many steps as whole 250s fit in the distance, the
   * first in its registration visit; meanwhile it may not attack.
   *
   * @param fromX the dying unit's point, along the width
   * @param fromY the dying unit's point, along the length
   */
  void flyBackFrom(int fromX, int fromY) {
    MovementState movement = unit.movement();
    GridEntity view = getView();
    int ringX = view.getX();
    int ringY = view.getY();
    movement.setTargetX(ringX);
    movement.setTargetY(ringY);
    view.setX(fromX);
    view.setY(fromY);
    view.setPrevX(fromX);
    view.setPrevY(fromY);
    long dx = ringX - fromX;
    long dy = ringY - fromY;
    movement.setBlockCountdown(isqrt(dx * dx + dy * dy) / DEATH_PUSHBACK_STEP);
    movement.setPushbackBudget(DEATH_PUSHBACK_STEP);
  }

  /** One step of a death spawn child's flight back to its ring point, in game units. */
  private static final int DEATH_PUSHBACK_STEP = 250;

  /** The integer square root, rounded down. */
  private static int isqrt(long value) {
    long root = (long) Math.sqrt((double) value);
    while (root * root > value) {
      root--;
    }
    while ((root + 1) * (root + 1) <= value) {
      root++;
    }
    return (int) root;
  }

  /**
   * Hands a played unit to its tunnel, as the construction does in place of its start: the level
   * setter has left it walking with no deploy time; it is moved onto its own king tower at its
   * flying height, its movement aimed at the placed point, and set to the spawn-pathfinding state,
   * where it walks its route hidden until it surfaces on the point.
   *
   * @param kingX its king tower's position along the width
   * @param kingY its king tower's position along the length
   * @param pointX the placed point along the width
   * @param pointY the placed point along the length
   */
  void tunnelFrom(int kingX, int kingY, int pointX, int pointY) {
    GridEntity view = getView();
    view.setState(GridEntityState.MOVING);
    view.setDeployCountdown(0);
    view.setX(kingX);
    view.setY(kingY);
    view.setZ(getData().flyingHeight());
    unit.movement().setExplicitX(pointX);
    unit.movement().setExplicitY(pointY);
    setter.setState(view, GridEntityState.SPAWN_PATHFIND);
    getTargetView().setAcceptsAttacker(false);
  }

  /** Sets the character deploying through its own setter, with its row's deploy time. */
  void startDeploying() {
    setter.setState(getView(), GridEntityState.DEPLOYING);
  }

  /**
   * Gives the character a deploy time of the spawn row's own: deploying, unless it is being set up
   * as a clone, and the countdown set to that time exactly.
   */
  void deployFor(int deployTimeMs) {
    if (getView().getState() != GridEntityState.CLONE_SETUP) {
      setter.setState(getView(), GridEntityState.DEPLOYING);
    }
    getView().setDeployCountdown(deployTimeMs);
  }

  /**
   * Starts the first-tick immunity of a spawned child: until its state visit has counted past the
   * attack-finish time, the sixth visit, it refuses every character that asks to target it.
   */
  void startSpawnImmunity() {
    unit.timers().setSpawnImmune(true);
    unit.timers().setSpawnImmuneElapsedMs(0);
    getTargetView().setAcceptsAttacker(false);
  }

  /** True while the character is a spawned child that may not be targeted yet. */
  public boolean isSpawnImmune() {
    return unit.timers().isSpawnImmune();
  }

  /**
   * Starts the character as a direct placement does: its row's starting action, when it has one,
   * built for it and scheduled with the row's own delay, the character as its cause. The placement
   * is outside every pending pass, so an action with no delay waits for the character's phase-1
   * pending pass of the tick, which runs before its first component visit.
   */
  public void start() {
    if (getData().onStartingAction() == null) {
      return;
    }
    BattleAction starting =
        world.getActions().build(getData().onStartingAction(), world.binding(this));
    actionHolder().schedule(starting, ActionHolder.OWN_DELAY, false, actionHolder());
  }

  /**
   * Takes another character row, as a data-changing action gives it. The target the character's
   * targeting component holds is read first. Then the row is swapped: the maximum hit points come
   * from the new row at the unchanged level and the hit points are kept; the reference is cleared
   * as it stands, with no timer reset and no route; and the collision radius, mass, speed and
   * targeting columns are the new row's from here on, so the next movement visit already moves at
   * the new speed. Last, a target it had is kept unless the row resets it or the validator refuses
   * it, and is then stored again through the setter, which prepares its route; a target given up
   * leaves the attack timing as it was. The new row's starting action does not run.
   *
   * <p>A walking unit may take a building row without a speed that drains over a lifetime, as the
   * Moving Cannon breaks down: its movement component is freed, it counts as a building, and its
   * hit points drain over the new row's lifetime from its next hit-points visit, what the drain
   * carried kept.
   *
   * <p>Refused rather than guessed: any other building row, a flying row either side, any other
   * swap that builds or frees the movement component or reaches a lifetime, a different rarity or
   * deploy time, and a champion. A shield keeps its value, its maximum taken from the new row.
   *
   * @param rowName the name of the new character row
   * @param resetTarget true to give up the target rather than keep it
   */
  @Override
  public void changeData(String rowName, boolean resetTarget) {
    UnitData next = world.getRecords().unit(rowName);
    refuseSwap(next);
    TargetingState targeting = getTargeting();
    TargetView target = isActive(TARGETING_SLOT) ? targeting.getReference() : null;
    boolean becomesBuilding = !getData().building() && next.building();
    swapRow(next);
    if (becomesBuilding) {
      // A walking unit that takes a building row without a speed: its movement component is
      // freed, and its hit points drain over the new row's lifetime from the next hit-points
      // visit, what the old drain carried kept. It keeps its state, position, lane and level.
      getHitPoints()
          .setDecayStep(HitPoints.decayStep(getHitPoints().getMaximum(), next.lifeTimeMs()));
      detach(MOVEMENT_SLOT);
      targeting.setMovementComponentActive(false);
      getView().setMovementComponent(false);
      getView().setMovementActive(false);
      // Everything that asks whether it is a building reads the new row from here on.
      getView().setBuilding(true);
      getView().setOccludes(true);
    }
    // The targeting component hears of the swap first: its reference is cleared as it stands.
    targeting.setReference(null);
    targeting.setKeptByPendingDamageCheck(false);
    targeting.setConfig(targetingConfig(next));
    getView().setCollisionRadius(next.collisionRadius());
    getView().setMass(next.mass());
    unit =
        new GridUnitState(
            unit.entity(),
            unit.movement(),
            unit.targeting(),
            unit.timers(),
            unit.movementConfig(),
            SpeedConfig.forGroundUnit(next.speed()),
            StateVisitConfig.forGroundUnit(next.deployTimeMs()),
            unit.selection(),
            unit.view());
    if (target != null) {
      SelectionChain selection = unit.selection();
      TargetView kept =
          !resetTarget && selection.validate(target, ReferenceValidator.MODE_RECHECK)
              ? target
              : null;
      ReferenceSetter.setReference(
          targeting, kept, false, false, false, selection, selection.getOutcome());
    }
  }

  /** Refuses a swap whose effect is not established. */
  private void refuseSwap(UnitData next) {
    UnitData current = getData();
    // A walking unit may take a building row that stands still and drains over a lifetime, as the
    // Moving Cannon breaks into its broken cannon; no other swap to a building is established.
    boolean breaksDown =
        !current.building()
            && current.speed() != 0
            && current.lifeTimeMs() == 0
            && next.building()
            && next.speed() == 0
            && next.lifeTimeMs() > 0;
    String refused = null;
    if (next.air() || current.air() || current.building() || next.building() && !breaksDown) {
      refused = "a building or a flying row";
    } else if (!breaksDown && (current.speed() == 0) != (next.speed() == 0)) {
      refused = "a movement component built or freed";
    } else if (!breaksDown && (current.lifeTimeMs() != 0 || next.lifeTimeMs() != 0)) {
      refused = "a lifetime";
    } else if (current.spawnCharacter() != null || next.spawnCharacter() != null) {
      refused = "a spawner";
    } else if (current.rarity() != next.rarity()) {
      refused = "a level packed against another rarity";
    } else if (current.deployTimeMs() != next.deployTimeMs()) {
      refused = "another deploy time";
    } else if (current.champion()) {
      refused = "a champion's controller";
    } else if (!Objects.equals(current.ability(), next.ability())) {
      refused = "another ability";
    } else if (current.chargeRange() != 0
        || next.chargeRange() != 0
        || current.jumpEnabled()
        || next.jumpEnabled()) {
      refused = "a charge or a river jump";
    } else if (current.dashCooldown() != 0 || next.dashCooldown() != 0) {
      refused = "a dash";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(
          name() + " taking " + next.name() + " asks for " + refused + ", which is not modelled");
    }
  }

  /** The row the character runs as it starts an attack, built on first use. */
  private BattleAction startingAttackRow;

  /** Schedules the character's starting-attack row on itself, the character as its cause. */
  private void startingAttack() {
    if (startingAttackRow == null) {
      startingAttackRow =
          world.getActions().build(getData().onStartingAttackAction(), world.binding(this));
    }
    actionHolder().schedule(startingAttackRow, ActionHolder.OWN_DELAY, false, actionHolder());
  }

  /**
   * Refuses the parts of a character's attack that are not established: an attack sequence whose
   * mode moves the index by itself, an entry that sets more than its damage and projectile, an
   * entry without a projectile on a unit that fires, and an action run as the character attacks.
   */
  private static void refuseAttack(UnitData data) {
    AttackSequence sequence = data.attackSequence();
    String refused = null;
    if (sequence.mode() != AttackSequence.MODE_NONE) {
      refused = "an attack sequence whose mode " + sequence.mode() + " moves the index itself";
    } else if (sequence.replacesAttack()) {
      for (int index = 0; index < sequence.order().size(); index++) {
        AttackSequence.Entry entry = sequence.entryAt(index);
        if (entry.overridesMore()) {
          refused = "an attack sequence entry that sets more than its damage and projectile";
        } else if (entry.projectile() == null && data.hasProjectile()) {
          refused = "an attack sequence entry without a projectile on a unit that fires";
        }
      }
    }
    if (refused == null && data.onAttackAction() != null) {
      refused = "an action run as it attacks";
    }
    if (refused == null && data.chargeRange() != 0 && data.hasProjectile()) {
      refused = "a charge on a unit that fires, whose charged shot is not established";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(data.name() + " has " + refused + ", not modelled");
    }
  }

  /** The children linked into this character's group, newest first. */
  public List<CharacterEntity> group() {
    return Collections.unmodifiableList(group);
  }

  /**
   * Links a child into this character's group right after the character, ahead of every child
   * linked before it.
   */
  @Override
  public void linkIntoGroup(SpawnHost child) {
    CharacterEntity linked = (CharacterEntity) child;
    group.add(0, linked);
    linked.groupSource = this;
    world.groupLinked(this, linked);
  }

  /**
   * Unlinks the character from the group it was linked into, as it is released.
   *
   * @return the character whose group it left, or null when it was in none
   */
  CharacterEntity leaveGroup() {
    CharacterEntity source = groupSource;
    if (source != null) {
      source.group.remove(this);
      groupSource = null;
    }
    return source;
  }

  /**
   * The character's view at placement: deploying, facing up the arena from the bottom side and down
   * it from the top side, in the lane of the road nearest to the deploy position; a building has no
   * movement component and occludes the cells it stands on.
   */
  private static GridEntity createView(
      TileMap tileMap, UnitData data, String name, int side, int x, int y) {
    GridEntity view = new GridEntity();
    view.setName(name);
    view.setSide(side);
    view.setCollisionRadius(data.collisionRadius());
    view.setMass(data.mass());
    // An air unit is created at its row's flying height and keeps it; the layer tests of the
    // contact passes compare that height, the validator the air answer.
    view.setAir(data.air());
    view.setZ(data.flyingHeight());
    view.setZTotal(data.flyingHeight());
    // A building has no movement component, and stands in the overlay as an obstacle.
    view.setBuilding(data.building());
    view.setOccludes(data.building());
    view.setMovementComponent(!data.building());
    view.setMovementActive(!data.building());
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
        .targetOnlyBuildings(data.targetOnlyBuildings())
        .attackSequenceMode(data.attackSequence().mode())
        .attackSequenceLength(data.attackSequence().order().size())
        .hasOnStartingAttackAction(data.onStartingAttackAction() != null)
        .crownTowerDamagePercent(data.crownTowerDamagePercent())
        .hasProjectile(data.hasProjectile())
        .areaDamageRadius(data.areaDamageRadius())
        .selfAsAoeCenter(data.selfAsAoeCenter())
        .overrideAttackFinishTime(data.overrideAttackFinishTime())
        .attackFinishTime(data.attackFinishTimeMs())
        .minimumRange(data.minimumRange())
        .keepChargingAfterAttack(data.keepChargingAfterAttack())
        .jumpHeight(data.jumpHeight())
        .dashCooldown(data.dashCooldown())
        .dashMinRange(data.dashMinRange())
        .dashMaxRange(data.dashMaxRange())
        .dashLandingTime(data.dashLandingTimeMs())
        .dashStopsAtContact(data.dashToTargetRadius())
        .dashImmuneToDamageTime(data.dashImmuneToDamageTimeMs())
        .targetOnlyTroops(data.targetOnlyTroops())
        .ignoreTargetsWithBuff(data.ignoreTargetsWithBuff() != null)
        .deprioritizeTargetsWithBuff(data.deprioritizeTargetsWithBuff())
        .build();
  }

  private boolean deploying() {
    return getView().getState() == GridEntityState.DEPLOYING;
  }

  /**
   * A character's death switches its movement component off, so the rest of the tick skips its
   * movement visit. Its targeting component stays on, so a hit it has due in the same tick still
   * lands; the standard game switches that off too only under a global setting it leaves off.
   */
  @Override
  protected void died() {
    getView().setMovementActive(false);
  }

  /**
   * After each projectile it launches, a unit whose row pushes it back asks for that pushback, away
   * from the projectile's aim: with the gates lifted, so even a unit whose row ignores pushback
   * recoils, as an attack's pushback, the whole distance whatever the separation, and refused only
   * while a pushback is still in flight. The pushback visit then flies it from the movement pass.
   */
  @Override
  public void launched(int aimX, int aimY) {
    int distance = getData().attackPushBack();
    if (distance < 1) {
      return;
    }
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            aimX,
            aimY,
            distance,
            true,
            true,
            false,
            false,
            false);
    world.pushbackRequested(
        this, ran == 1 && movement.getPushbackInFlight() == 1, aimX, aimY, movement);
  }

  /**
   * An area's push on the character, after the area's damage: a character that stands without a
   * movement component, whose row ignores pushback or that the damage killed is left where it is.
   * Otherwise its movement component is switched on and a pushback is asked for, away from the
   * point, with every gate in place and nothing lifted.
   *
   * @param x the point it is pushed away from, along the width
   * @param y the point it is pushed away from, along the length
   * @param distance how far
   * @return true when the pushback was asked for
   */
  boolean pushedByArea(int x, int y, int distance) {
    if (!getView().isMovementComponent()
        || getData().ignorePushback()
        || !HitPoints.alive(getHitPoints())) {
      return false;
    }
    getView().setMovementActive(true);
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            distance,
            false,
            false,
            false,
            false,
            false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
    return true;
  }

  /**
   * A push from a hit along a projectile's way: only a character whose movement is on and that is
   * not waiting to deploy is pushed, away from the projectile, with the gates that would refuse it
   * lifted when the projectile's row pushes all.
   *
   * @param x the projectile's position along the width
   * @param y the projectile's position along the length
   * @param distance how far
   * @param liftGates true when the row pushes all
   */
  void pushedByTravellingHit(int x, int y, int distance, boolean liftGates) {
    if (!getView().isMovementComponent() || !getView().isMovementActive() || waiting()) {
      return;
    }
    MovementState movement = unit.movement();
    int ran =
        PushbackRequest.request(
            movement,
            getView(),
            pushbackQueries,
            x,
            y,
            distance,
            liftGates,
            false,
            false,
            false,
            false);
    world.pushbackRequested(this, ran == 1 && movement.getPushbackInFlight() == 1, x, y, movement);
  }

  /** Hidden while it tunnels to its placement, in the spawn-pathfinding state. */
  @Override
  public boolean hidden() {
    return getView().getState() == GridEntityState.SPAWN_PATHFIND;
  }

  /**
   * Untouchable while it tunnels, while it rides on a parent, while it dashes under a row with a
   * dash immunity, and, when asked, while that immunity lasts after the dash.
   */
  @Override
  boolean untouchable(boolean dashImmunity) {
    return hidden()
        || parent != null
        || getView().getState() == GridEntityState.DASHING
            && getData().dashImmuneToDamageTimeMs() > 0
        || dashImmunity && unit.timers().getDashImmunityRemainingMs() >= 1;
  }

  /** A dasher that lost its reference resumes, as the state visit's resume does. */
  @Override
  protected void resumeAfterDrop() {
    resume();
  }

  /**
   * The resume: the state the character should be in now that what held it has ended - moving,
   * standing, or nothing. A dasher that lost its reference asks for it, and so does a tiebreaker's
   * clearing before its kill.
   */
  void resume() {
    ResumeHelper.resume(getView(), unit.stateConfig(), stateQueries(), new ArrayList<>(), setter);
  }

  /**
   * The landing of a dash, as the follower asks for it where the dash ends. Its damage is the row's
   * dash damage at the character's level, under a hit id of its own. With a dash radius it hits the
   * area around the landing point and pushes what it hits; without, it hits the reference alone,
   * when the shared validator accepts it and it is within the attack range widened by 500, along
   * the character's facing, and then resets the attack and reloads the wind-up, so the next hit
   * comes a whole hit speed later; a character that stopped on a cell it may not stand on is moved
   * off it. Then the moving state is asked for, or, for a row with a landing time, the landing hold
   * starts.
   */
  private void landDash() {
    UnitData data = getData();
    GridEntity view = getView();
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            data.dashDamage(),
            getPackedLevel(),
            ScalingMode.CARD_DAMAGE,
            data.rarity());
    int hitId = world.nextHitId();
    WorldEntity hit = null;
    boolean area = data.dashRadius() >= 1;
    if (area) {
      world.dashLandingArea(this, damage, hitId);
    } else {
      TargetingState t = unit.targeting();
      TargetView reference = isActive(TARGETING_SLOT) ? t.getReference() : null;
      if (reference != null
          && ReferenceValidator.sharedValidate(
              t,
              reference,
              false,
              data.targetOnlyBuildings(),
              false,
              false,
              ValidatorQueries.standard1v1())
          && RangeTest.referenceInRange(t, reference, DASH_HIT_EXTENSION)) {
        hit = world.entityOf(reference.getEntity());
        world.dealDamage(this, reference, damage, view.getDirX(), view.getDirY());
        t.clearAttack();
        t.setLoadTimerMs(data.loadTimeMs());
      }
      if ((CellTests.cellBlocked(world.getGrid(), view.getX(), view.getY()) & 1) != 0) {
        throw new UnsupportedOperationException(
            name()
                + " landed its dash on a cell it may not stand on, whose move off it no run"
                + " holds");
      }
    }
    if (data.dashLandingTimeMs() < 1) {
      movementRequests.requestState(GridEntityState.MOVING);
    } else if (view.getBlockCountdownMs() == 0) {
      view.setBlockCountdownMs(LANDING_HOLD_START_MS);
    }
    world.dashLanded(this, hit, hit != null || area ? damage : 0, area);
  }

  /**
   * Attaches the character to a parent it rides on, as its parent's spawner makes it: from its next
   * movement visit on it is placed around the parent, and meanwhile nothing can hurt, target or
   * push it.
   *
   * @param parent the character it rides on
   * @param angle its angle on the parent's ring
   */
  void attachTo(CharacterEntity parent, int angle) {
    this.parent = parent;
    this.attachAngle = angle;
    parent.riders.add(this);
    getView().setAttached(true);
    unit.timers().setAttached(true);
    getTargetView().setAcceptsAttacker(false);
  }

  /** The characters riding on this one, in the order they were made. */
  public List<CharacterEntity> riders() {
    return Collections.unmodifiableList(riders);
  }

  /**
   * The last part of the removal notice: a rider whose parent left is let go. It forgets the
   * parent, runs its death slot - not the death handler - and is removed in the same cleanup.
   */
  @Override
  protected void parentRemoved(BattleEntity removed) {
    if (parent == null || parent != removed) {
      return;
    }
    CharacterEntity left = parent;
    parent = null;
    getView().setAttached(false);
    unit.timers().setAttached(false);
    world.parentLeft(this, left);
    released = true;
  }

  /** The state visit's removal request, or the release by a parent that left. */
  @Override
  protected boolean removalRequested() {
    return released || morphed || unit.timers().isRemovalRequested();
  }

  /** True once it has been morphed into another object, which removes it at the next cleanup. */
  private boolean morphed;

  /** Marks the character morphed into another object: it leaves at the closing cleanup. */
  void morphedAway() {
    morphed = true;
  }

  /**
   * Makes the object a surfaced unit morphs into: on its point, at its level and in its lane, in
   * the state its row is made in - a building stands, with no deploy time yet - with the unit's
   * share of its hit points, and, for a building, facing as the unit did. Refused rather than
   * guessed: a unit that rides on another, one that carries buffs, one out of the deploying and
   * waiting states, a row of another rarity, and a morph into anything but a building.
   *
   * @param old the unit that surfaced
   * @param data the row it morphs into
   * @return the new object, not yet handed to the holder
   */
  static CharacterEntity morphedFrom(CharacterEntity old, UnitData data) {
    GridEntity from = old.getView();
    String refused = null;
    if (old.parent != null) {
      refused = "a unit that rides on another";
    } else if (!old.getBuffs().items().isEmpty()) {
      refused = "a unit carrying buffs, which the new object would copy";
    } else if (from.getState() != GridEntityState.DEPLOYING
        && from.getState() != GridEntityState.WAITING_TO_DEPLOY) {
      refused = "a unit out of the deploying and waiting states";
    } else if (old.getData().rarity() != data.rarity()) {
      refused = "a row of another rarity";
    } else if (!data.building()) {
      refused = "a morph into a character";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(
          old.name() + " morphing into " + data.name() + " asks for " + refused + ", not modelled");
    }
    CharacterEntity made =
        new CharacterEntity(
            old.world,
            data,
            old.name() + "_" + data.name(),
            old.side(),
            from.getX(),
            from.getY(),
            PackedLevel.level(old.getPackedLevel()),
            from.getLane(),
            -1);
    GridEntity view = made.getView();
    view.setState(GridEntityState.STANDING);
    view.setDeployCountdown(0);
    view.setDirX(from.getDirX());
    view.setDirY(from.getDirY());
    made.takeHitPointShare(old);
    return made;
  }

  /**
   * Sets a morph's new object deploying through its setter, as the morph sets it to the unit's
   * state: the entry seeds its deploy time and makes its row's area object.
   */
  void startDeployingAfterMorph() {
    setter.setState(getView(), GridEntityState.DEPLOYING);
  }

  /** True while the character waits its turn to deploy: none of its components is visited. */
  private boolean waiting() {
    return getView().getState() == GridEntityState.WAITING_TO_DEPLOY;
  }

  /**
   * What the state visit asks of the character: its team, whether it can hold a route - only with a
   * movement component - and whether it has hit points.
   */
  private StateQueries stateQueries() {
    StateQueries queries = StateQueries.forUnitWithRoute(side() & 1);
    return new StateQueries(
        queries.team(),
        getView().isMovementComponent(),
        queries.gridAllowsRoute(),
        queries.gridRouteFlag(),
        getHitPoints() != null,
        queries.abilityCastActive(),
        this::abilityGate,
        queries.protectedFromDamage(),
        queries.protectionApplies(),
        queries.goalRow(),
        getBuffs().speed(DEPLOY_STEP_MS));
  }

  /** The movement pass's answers for the character as it stands now, reference included. */
  private GridMovementQueries movementQueries() {
    return new GridMovementQueries(unit, world.getGrid(), world.getCosts(), world::unitStateOf)
        .withBuffs(getBuffs().speedPercents(), getBuffs().speed(FOLLOWER_STEP));
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
            queries)
        .withRequests(movementRequests);
  }

  /**
   * Refuses a hit that reaches several targets or, without a projectile, applies a buff to what it
   * hits: neither is modelled.
   */
  @Override
  protected void refuseHit() {
    UnitData data = getData();
    // A unit that fires hands its hit to its projectile, so its own buff on damage is never
    // applied: the Witch Mother's curse comes from her projectile's target buff.
    if (data.multipleTargets() >= 2 || data.buffOnDamage() != null && data.projectile() == null) {
      throw new UnsupportedOperationException(
          name()
              + " hits with MultipleTargets "
              + data.multipleTargets()
              + " and BuffOnDamage "
              + data.buffOnDamage()
              + ", which are not modelled");
    }
  }

  /**
   * The end of a Kamikaze row's hit, after its direct hit or its launch, landed or not: the unit
   * kills itself with its whole hit points, as its own attacker on its own side, so its death slot
   * runs in the same pass and it leaves at the tick's closing cleanup; a projectile it launched
   * flies on without it. A death-spawn buff it carries would be deleted without its death spawn,
   * and a shield of its own would take the kill; neither is modelled.
   */
  @Override
  protected void hitEnded() {
    if (!getData().kamikaze()) {
      return;
    }
    for (BuffInstance instance : getBuffs().items()) {
      if (instance.getBuff().deathSpawn() != null) {
        throw new UnsupportedOperationException(
            name()
                + " ends a Kamikaze hit carrying "
                + instance.getBuff().name()
                + ", whose deletion without its death spawn is not modelled");
      }
    }
    if (getHitPoints() == null || getHitPoints().getShield() > 0) {
      throw new UnsupportedOperationException(
          name() + " ends a Kamikaze hit without hit points or with a shield, not modelled");
    }
    world.kamikazeKill(this);
  }

  /** The progress of the character's charge, while its movement component is on. */
  @Override
  protected int chargeProgress() {
    return isActive(MOVEMENT_SLOT) ? unit.movement().getChargeProgress() : HitQueries.NO_CHARGE;
  }

  /** The damage of the character's charged hit: its row's special damage at its level. */
  @Override
  protected int chargedDamage() {
    return LevelScaling.scale(
        ScalingGlobals.standard(),
        getData().damageSpecial(),
        getPackedLevel(),
        ScalingMode.CARD_DAMAGE,
        getData().rarity());
  }

  /**
   * The charge reset: the progress back to 0 for a row with a charge range and to no charge for one
   * without, and the targeting component's strike-now byte cleared. A buff that gives a charge
   * range is refused with its row.
   */
  @Override
  protected void resetCharge() {
    unit.movement()
        .setChargeProgress(getData().chargeRange() != 0 ? 0 : MovementState.CHARGE_INACTIVE);
    unit.targeting().setChargeStrike(false);
  }

  /**
   * The entity state visit: the deploy countdown and every other per-tick state transition. A
   * spawned child's immunity is counted here, and once it clears the child accepts attackers again.
   * A building's deploy ends in the standing state through the resume, and its targeting component
   * visits it from the next tick. Where the visit reaches its spawner block, the spawner runs, and
   * where it reaches its tail, the combat gate: a dead character drops its reference there.
   */
  @Override
  protected void postHook() {
    List<String> calls = new ArrayList<>();
    EntityStateVisit.stateVisit(
        getView(),
        unit.timers(),
        unit.movement(),
        unit.stateConfig(),
        StateVisitGlobals.standard(),
        stateQueries(),
        calls,
        setter);
    // An attached rider answers no attacker at all; a spawned child none while it is immune, and
    // a tunnelling unit none while it is hidden.
    getTargetView()
        .setAcceptsAttacker(!unit.timers().isSpawnImmune() && parent == null && !hidden());
    // The visit's removal of an object without hit points - the resume at the end of a bomb's
    // deploy - calls its death slot, without the death handler.
    if (calls.contains("remove")) {
      world.deathAtRemoval(this);
    }
    if (calls.contains("elixir")) {
      collectElixir();
    }
    // A unit that surfaced morphs into its row's morph, which is made, queued and deploying.
    if (calls.contains("spawn_pathfind_morph")) {
      world.morph(this);
    }
    if (calls.contains("spawner")) {
      spawner();
    }
    // The landing hold's end resets the attack and reloads the wind-up, with the targeting
    // component on, as the moving state is asked for.
    if (calls.contains("dash_landed") && isActive(TARGETING_SLOT)) {
      unit.targeting().clearAttack();
      unit.targeting().setLoadTimerMs(getData().loadTimeMs());
    }
    if (calls.contains("targeting_visit")) {
      throw new UnsupportedOperationException(
          name() + " hides until it attacks, whose deploy end runs the gate and a targeting visit");
    }
    // The ability's effect fires on the visit its trigger delay reaches zero.
    if (calls.contains("ability_warning")) {
      abilityFired();
    }
    // The visit's tail call: the combat gate.
    if (!calls.isEmpty() && calls.get(calls.size() - 1).equals("visit_tail")) {
      stateTailGate();
    }
  }

  /** The combat gate, as the state visit's tail and a change into or out of a cast run it. */
  private void stateTailGate() {
    combatGate(isActive(TARGETING_SLOT) && !deploying() && !waiting(), setter::prepareRoute);
  }

  /**
   * The ability gate: whether a requested ability may start now. It may when the unit has an
   * ability, is not a champion's clone, can act - its targeting component on - is in none of the
   * states from dashing to the follow-up's, carries neither the postponing nor the disabling tag,
   * and the ability does something: with every other effect refused as it is requested, it runs an
   * activation action.
   */
  private boolean abilityGate() {
    AbilityData ability = getData().ability();
    if (ability == null || !isActive(TARGETING_SLOT)) {
      return false;
    }
    int state = getView().getState();
    if (state >= GridEntityState.DASHING && state <= GridEntityState.COMPONENTS_DISABLED) {
      return false;
    }
    if ((getView().getFlags() & (EntityFlags.ABILITY_POSTPONED | EntityFlags.ABILITY_DISABLED))
        != 0) {
      return false;
    }
    return ability.onActivationAction() != null;
  }

  /**
   * Requests the unit's ability, as a friend collector does: with the gate open the unit enters the
   * casting state now, through its setter; shut, the ability is left pending, which the state
   * visit's pending branch turns into the cast on the first visit the gate opens. A unit without an
   * ability does nothing. An ability that does more than run its activation action, or keeps a buff
   * on a unit waiting to cast, is refused.
   */
  public void requestAbility() {
    AbilityData ability = getData().ability();
    if (ability == null) {
      return;
    }
    if (!ability.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          name()
              + " casts "
              + ability.name()
              + ", which sets columns the battle does not model: "
              + ability.unmodelledColumns());
    }
    boolean now = abilityGate();
    world.abilityRequested(this, now);
    if (now) {
      setter.setState(getView(), GridEntityState.CASTING);
      return;
    }
    getView().setPendingFlags(getView().getPendingFlags() | EntityFlags.ABILITY_COOLDOWN_PAUSED);
    unit.timers().setAbilityReady(true);
  }

  /**
   * The ability's effect, on the visit its trigger delay reaches zero: its activation action is
   * scheduled on the unit, the unit as its cause. From the post-hooks it waits for the phase-3
   * pending pass. Its other effects are refused as it is requested.
   */
  private void abilityFired() {
    AbilityData ability = getData().ability();
    world.abilityFired(this);
    if (ability.onActivationAction() != null) {
      BattleAction action =
          world.getActions().build(ability.onActivationAction(), world.binding(this));
      actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, actionHolder());
    }
  }

  /**
   * What a friend-collecting run of the unit asks of the battle: the object query around it, the
   * live list by id, the guarded squared distance from it, its tag and hit speed, the target locks,
   * its ability, and the hooks and projectiles the run schedules and fires.
   */
  @Override
  public FriendCollecting friendCollecting() {
    return new FriendCollecting() {
      @Override
      public int ownerId() {
        return getId();
      }

      @Override
      public List<Integer> query(int radius, GameObjectFilter filter) {
        List<Integer> ids = new ArrayList<>();
        for (WorldEntity entity : world.objectQuery(CharacterEntity.this, radius, filter)) {
          ids.add(entity.getId());
        }
        return ids;
      }

      @Override
      public boolean found(int id) {
        return world.liveObject(id) instanceof CharacterEntity;
      }

      @Override
      public int squaredDistance(int id) {
        GridEntity other = ((WorldEntity) world.liveObject(id)).getView();
        return FixedMath.squaredDistance(
            getView().getX(), getView().getY(), other.getX(), other.getY());
      }

      @Override
      public boolean noAttack() {
        return (getView().getFlags() & EntityFlags.NO_ATTACK) != 0;
      }

      @Override
      public int hitSpeed(int stepMs) {
        return getBuffs().hitSpeed(stepMs);
      }

      @Override
      public TargetLocks locks() {
        return world.locks();
      }

      @Override
      public void requestAbility() {
        CharacterEntity.this.requestAbility();
      }

      @Override
      public void schedule(int targetId, BattleAction action) {
        WorldEntity target = (WorldEntity) world.liveObject(targetId);
        BattleAction built = world.getActions().build(action.name(), world.binding(target));
        target.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, actionHolder());
      }

      @Override
      public void launch(ProjectileData projectile, int friendId) {
        world.launchAt(CharacterEntity.this, projectile, (WorldEntity) world.liveObject(friendId));
      }
    };
  }

  @Override
  protected boolean keepsTargetWhileCasting() {
    return getData().ability() != null && getData().ability().keepCurrentTarget();
  }

  /**
   * The spawner block of the state visit, for a row with a spawn character, a building's or a
   * walking unit's alike: from the end of its deploy, each visit that reaches the block - walking,
   * attacking or pushed - takes half the spawn rate - 50 ms without a buff, none under a stun - off
   * its timer, and at 0 or below it fires: one child for a row with an interval, the whole wave at
   * once at the row's spawn radius for one without. The next firing is the interval away within a
   * wave, or the pause away once the wave is made, and never less than 1 ms; a timer that went
   * below 0 carries.
   */
  /**
   * The elixir block of an elixir collector's state visit: its timer gains half the spawn step a
   * visit from the end of its deploy, and once it reaches the row's generation time the collector
   * pays its king the row's amount - but only while that amount and the king's whole elixir come to
   * no more than MAX_MANA. Otherwise the timer is held at the generation time and it tries again
   * the next visit. A payout takes the generation time off the timer.
   */
  private void collectElixir() {
    UnitData data = getData();
    if (data.manaCollectAmount() <= 0) {
      return;
    }
    KingElixir kings = world.getKingElixir();
    if (kings == null) {
      throw new UnsupportedOperationException(
          name() + " collects elixir outside a match, where no king holds any");
    }
    int period = data.manaGenerateTimeMs();
    collectorTimerMs += getBuffs().spawnRate() / 2;
    if (collectorTimerMs < period) {
      return;
    }
    int amount = data.manaCollectAmount();
    if (amount + kings.wholeElixir(side()) > kings.maxMana()) {
      collectorTimerMs = Math.min(collectorTimerMs, period);
      return;
    }
    collectorTimerMs -= period;
    kings.add(side(), amount * KingElixir.SCALE);
    world.elixirCollected(this, amount);
  }

  private void spawner() {
    UnitData data = getData();
    int interval = data.spawnIntervalMs();
    int pause = data.spawnPauseTimeMs();
    // With no limit, a spawner fires only with a time between its firings.
    if (data.spawnCharacter() == null || interval + pause <= 0) {
      return;
    }
    spawnTimer -= getBuffs().spawnRate() / 2;
    if (spawnTimer > 0) {
      return;
    }
    int number = data.spawnNumber();
    int count = interval != 0 ? 1 : number;
    int radius = interval != 0 ? 0 : data.spawnRadius();
    world.liveSpawn(this, count, radius);
    spawnWaveMade += count;
    int next;
    if (spawnWaveMade < number) {
      next = interval;
    } else {
      spawnWaveMade = 0;
      next = pause;
    }
    spawnTimer = spawnTimer + next > 1 ? spawnTimer + next : 1;
    world.spawnerFired(this, data.spawnCharacter(), count, radius, spawnTimer, spawnWaveMade);
  }

  /** Chooses, keeps or drops the character's target and decides whether it attacks this tick. */
  private final class TargetingComponent implements BattleComponent {

    @Override
    public int index() {
      return TARGETING_SLOT;
    }

    @Override
    public void visit() {
      if (deploying() || waiting()) {
        return;
      }
      if (parent != null && (parent.getView().getFlags() & EntityFlags.NO_ATTACK) != 0) {
        throw new UnsupportedOperationException(
            name() + " rides on a parent that may not attack, whose hold on its hit no run holds");
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

  /**
   * The hit points' own visit, in the third component pass: outside the deploying, waiting and
   * spawn-pathfinding states, and while it has hit points, the lifetime decay takes its step; the
   * step that takes the last hit point is a death of its own.
   */
  private final class HitPointsComponent implements BattleComponent {

    @Override
    public int index() {
      return HIT_POINTS_SLOT;
    }

    @Override
    public void visit() {
      // A shield that is up is tagged in every state and at any hit points, from the next step.
      if (getHitPoints().getShield() >= 1) {
        getView().setPendingFlags(getView().getPendingFlags() | GameTags.HAS_SHIELD);
      }
      int state = getView().getState();
      if (getHitPoints().getHitPoints() < 1
          || state == GridEntityState.DEPLOYING
          || state == GridEntityState.WAITING_TO_DEPLOY
          || state == GridEntityState.SPAWN_PATHFIND) {
        return;
      }
      int before = getHitPoints().getHitPoints();
      if (decay()) {
        world.decayDeath(CharacterEntity.this, before);
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
      speedBudget = 0;
      // Switched off while the unit waits its turn to deploy, and from its death on.
      if (!getView().isMovementActive()) {
        return;
      }
      // A deploying unit is visited too: it asks for no route and gets no speed, so only a push
      // from another unit can move it. A rider is placed around its parent and nothing else.
      GridMovementQueries queries = movementQueries();
      if (parent != null) {
        UnitData data = getData();
        MovementVisit.movementVisit(
            unit.movement(),
            getView(),
            new AttachedParent(parent.getView(), attachAngle),
            MovementConfig.forRider(
                data.spawnAngleShift(),
                data.spawnMaxAngle(),
                data.spawnAttachMaxRotation(),
                data.flyingHeight()),
            MovementConfig.forParent(parent.getData().spawnRadius()),
            queries,
            false,
            movementChain(queries));
        return;
      }
      MovementVisit.movementVisit(
          unit.movement(),
          getView(),
          null,
          unit.movementConfig(),
          null,
          queries,
          false,
          movementChain(queries));
      speedBudget = queries.lastSpeedBudget();
      for (int[] r : queries.relocations()) {
        world.relocated(CharacterEntity.this, r[0], r[1], r[2], r[3]);
      }
      boolean full = unit.movement().getChargeProgress() >= MovementState.CHARGE_COMPLETE;
      if (charged && !full) {
        world.chargeLost(CharacterEntity.this);
      }
      charged = full;
    }
  }
}
