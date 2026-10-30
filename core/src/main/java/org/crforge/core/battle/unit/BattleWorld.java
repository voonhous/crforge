/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleRandom;
import org.crforge.core.battle.EntityHolder;
import org.crforge.core.battle.HolderPasses;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.AirToGround;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.CannonProjectileSpawn;
import org.crforge.core.battle.action.CardDeployListener;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.ConeShape;
import org.crforge.core.battle.action.CreateParallelProjectiles;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.OverrideAbilityButtonState;
import org.crforge.core.battle.action.RunActionOnTroopDestroyed;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.action.TakeDamage;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.data.ActionRows;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.Formation;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.filter.FilterSubject;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.projectile.ProjectileChain;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.projectile.ProjectileLauncher;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.battle.spawn.SpawnPassable;
import org.crforge.core.battle.spawn.SpawnPerform;
import org.crforge.core.battle.spawn.SpawnPlacement;
import org.crforge.core.battle.spawn.SpawnRow;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.GridUnitState;
import org.crforge.core.pathfinding.IndexNeighbourQuery;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.grid.CellCosts;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.CellTests;
import org.crforge.core.pathfinding.grid.FootprintOverlay;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Relocation;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.index.SegmentTests;
import org.crforge.core.pathfinding.index.ShapeTests;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.index.SpatialQuery;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.ContactRule;
import org.crforge.core.pathfinding.move.MovementGlobals;
import org.crforge.core.pathfinding.move.NeighbourQuery;
import org.crforge.core.pathfinding.state.StateQueries;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * What the entities of one battle share: the arena's routing grid with its building overlay, and
 * the spatial index every target selection and neighbour query reads.
 *
 * <p>Both are per-tick structures. The pre-pass rebuilds them from the tick's snapshot before any
 * entity is visited, in snapshot order, which is ascending entity id; the post-pass retires them.
 * An entity that moves during the tick is therefore found where it stood at the head of the tick,
 * while the shape tests that follow a lookup read its live position.
 *
 * <p>The world owns the battle's entity holder, so that a character's hit can hand the projectiles
 * it launches to the holder in the tick of the hit. Projectiles are entities of the same holder but
 * stand on no cell: the index and the overlay are built from the arena entities alone.
 *
 * <p>The world is also where a hit's or an impact's damage reaches its target, and where anything
 * outside the tick that wants to watch the arena attaches: an observer is told where the tick's
 * visits begin and end, about every hit that lands, about every projectile launched and arrived,
 * and about every arena entity that leaves. What it is told is the world's {@link WorldEvents}.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the index and the overlay are rebuilt in the pre-pass from the id-ordered"
            + " snapshot of the arena entities and retired in the post-pass, the overlay's"
            + " per-side change flags are copied once per tick, a projectile is in neither and is"
            + " handed to the holder in the tick of its launch, and a dead entity leaves the"
            + " holder in the closing cleanup of the tick it dies - the king tower excepted,"
            + " which never does - when every arena entity is told at once and its default target"
            + " lists lose it; the death handler scheduling the death action and, unless the"
            + " entity killed itself, the killed action on the dying entity with its killer as"
            + " the cause, and a champion handed over after its spawn with no effect on it; the"
            + " death slot inside the killing hit - the death damage around the dying entity with"
            + " its pushback, and the death spawn on its ring - held by the reference battles"
            + " golem_death_pushes_minipekka_pekka, troops_golem_dies_mid_fight, card_Tombstone"
            + " and hero_tombstone; the"
            + " death spawn's children flying back to the ring, held by"
            + " golem_death_pushes_minipekka_pekka; a ring turned over by the dying object's lane"
            + " and team, its children given a fixed priority, held by card_SkeletonBalloon and"
            + " evo_skeletonballoon_vs_musketeer; a single child on the dying object's point and"
            + " a bomb's death slot as its deploy ends, without the death hooks, held by"
            + " giant_skeleton_bomb_pushes_knight and card_GiantSkeleton; several death spawn"
            + " children in front of the dying object, a lifetime's death without the death"
            + " handler and a building spawner's children in front of it, held by card_Tombstone"
            + " and card_GoblinHut; an area effect created by a death or placed directly and its"
            + " hits dealt, held by status_lumberjack_death_rage, the spell reference battles and"
            + " card_RageBarbarian, whose bottle, a child without hit points, deploys for its"
            + " row's deploy time though its spawn does not ask; a troop card's projectile cast"
            + " before its units and a unit's push as it enters the deploying state, finding"
            + " nobody in a card play's command pass, held by card_MegaKnight,"
            + " card_MegaKnight_until_stop and grid_log_under_megaknight_jump, and the push's"
            + " tests on what it finds by a unit that waits its turn, held by no run; a"
            + " travelling hit's test of a kill by the damage queued for what it hit, which client"
            + " 16.635.1 makes without asking a global, traced on that client and held by no"
            + " reference of it yet. Refused: a"
            + " death whose row sets a column of the slot not modelled, a least radius that"
            + " draws, a child without hit points that has a range, a spawner's turned or drawn"
            + " ring and a spawner child without hit points, a building, pathing or starting an"
            + " action, and a death hook with no attacker or no pending pass ahead of it. Left"
            + " out: the elixir a death gives. Not modelled: the game mode's own per-tick work"
            + " beside the index and the overlay, and the copy of the attacker the game makes as"
            + " a death hook's cause. Traced on game client 16.635.1, held by no reference yet:"
            + " the drain's buff on damage of a direct hit taken from the attacker's attack"
            + " sequence entry where it sets one (WorldEntity), and nothing applied for a time"
            + " of 0. Still refused, reached by no row: a buff on damage beside an area radius,"
            + " whose area branch the 16.635.1 drain no longer has; the rest of that path is not"
            + " traced. Also traced on 16.635.1, no reference holds it yet: the global"
            + " LOGIC_SKIP_KAMIKAZE_ON_DISCARDED_HIT read from the tables, which are refused when"
            + " they do not publish it.")
public class BattleWorld implements HolderPasses {

  @Getter private final TileMap tileMap;

  /** The battle's entities, ticked in the holder's order; this world is its passes. */
  @Getter private final EntityHolder holder;

  /** The routing grid: the static cell map and the live building overlay. */
  @Getter private final CellGrid grid;

  /** The spatial index, populated only between the pre-pass and the post-pass. */
  @Getter private final SpatialIndex index;

  /** The validator's answers only the battle can give, about the damage on its way to a target. */
  @Getter private final ValidatorQueries validatorQueries = new BattleValidatorQueries(this);

  @Getter private final CellCosts costs = CellCosts.standard();

  /**
   * The match-wide movement settings: the standard game's, with a pushback's end dropping the
   * route, and once the tables are loaded the pushback's charge reset their global asks for.
   */
  @Getter private MovementGlobals movementGlobals;

  /**
   * The global under which a pushback's visit resets the charge of an entity whose movement is
   * disabled: published by the data of game client 16.635.1 (set), not by the data versions of
   * client 16.402.17, which run without the reset.
   */
  static final String RESET_CHARGE_DURING_PUSHBACK =
      "FIX_RESET_CHARGE_DURING_PUSHBACK_WHEN_MOVEMENT_DISABLED";

  /** The neighbour answers the push pass and the avoidance handler ask for. */
  @Getter private final NeighbourQuery neighbourQuery;

  /** This tick's arena entities in ascending id. */
  private final List<WorldEntity> present = new ArrayList<>();

  /** The entities of {@link #present} the pre-pass before did not list, in the same order. */
  private final List<WorldEntity> arrivals = new ArrayList<>();

  /** How many pre-passes the world has run; the number of the last one. */
  private int prePasses;

  /** This tick's views in the same order: what the index and the overlay are built from. */
  private final List<GridEntity> views = new ArrayList<>();

  /** This tick's projectiles in ascending id: the ones the tick visits. */
  private final List<ProjectileEntity> projectiles = new ArrayList<>();

  /**
   * Every arena entity admitted so far and not yet gone, keyed by its view. An entity joins at its
   * first pre-pass and leaves at the cleanup that removes it.
   */
  private final Map<GridEntity, WorldEntity> known = new IdentityHashMap<>();

  /**
   * The projectile deflection: the deflecting area effects of the tick and the passes over them.
   */
  private final ProjectileDeflection deflection = new ProjectileDeflection(this);

  /** The battle's hit counter: every hit takes the next id from it. */
  private int hitCounter;

  /**
   * The battle's projectile group counter, apart from the hit counter: a projectile that shares a
   * group with the ones its impact spawns takes the next id from it as it is launched.
   */
  private int projectileGroupCounter;

  /** How many buff instances the battle has listed, which names the next. */
  private int buffKeys;

  /** The most victims a death's damage takes. */
  private static final int DEATH_DAMAGE_LIMIT = 1000;

  /**
   * The least speed column a hooked target's pull scales the drag speed by: the step scales the
   * drag speed by the target's speed column as a percentage, floored at 60 (the speed of a
   * medium-speed unit), so a slow target such as a Bowler or a Giant comes back at a Knight's pace.
   */
  public static final int HOOK_PULL_SPEED_FLOOR = 60;

  /** The least speed column the other drag, the hook that stands and moves its owner, scales by. */
  public static final int MIN_HOOK_SPEED_FLOOR = 30;

  /** The most characters an area effect's buff reaches in one hit. */
  private static final int AREA_EFFECT_BUFF_LIMIT = 0x10000;

  /** The state the battle's random source starts from when no seed is given. */
  public static final int DEFAULT_SEED = 1;

  /**
   * The battle's one random source, which every draw of the battle takes in turn: an expression's
   * draw is taken where the expression is evaluated.
   */
  @Getter private BattleRandom random = new BattleRandom(DEFAULT_SEED);

  /** The state the battle's random source was last started from: {@link #DEFAULT_SEED} or given. */
  @Getter private int seed = DEFAULT_SEED;

  /**
   * Starts the battle's random source from a state of its own, before anything draws.
   *
   * @param seed the state the first draw steps
   */
  public void seed(int seed) {
    random = new BattleRandom(seed);
    this.seed = seed;
  }

  /**
   * The published global that makes a death spawn's children untargetable at first: on in the
   * standard game.
   */
  static final boolean DEATH_SPAWN_IMMUNE_FIRST_TICK = true;

  /** How many children each source has spawned so far, by the source's name. */
  private final Map<String, Integer> spawnCounts = new HashMap<>();

  /** How many clones the battle has made of each unit, by its name. */
  private final Map<String, Integer> cloneCounts = new HashMap<>();

  /** Those watching the arena from outside the tick, in the order they were added. */
  private final WorldEvents events = new WorldEvents(this);

  /** Whether an observer reads the champion slots' views ({@link #championViews}). */
  private boolean championViewsWanted;

  /**
   * The runs listening for destroyed objects, in the order they started listening: each hears of
   * every object whose death slot starts until it is let go.
   */
  private final List<RunActionOnTroopDestroyed.Listener> destroyedListeners = new ArrayList<>();

  /** The variables the battle's expressions may name, by name, each with its key. */
  private final Map<String, Integer> variableKeys = new HashMap<>();

  /**
   * The start value of each declared variable whose row sets DefaultValue, by key: what an
   * expression reads of the variable from an object no action has written it for.
   */
  private final Map<Integer, Integer> variableStarts = new HashMap<>();

  /**
   * One typed hit waiting for the drain.
   *
   * @param source the entity that deals it, or null for none
   * @param target the entity it lands on
   * @param type its damage type
   * @param amount its amount, before the type's pipeline
   * @param directionX the target's position less the source's, along the width
   * @param directionY the same along the length
   */
  private record TypedHit(
      WorldEntity source,
      WorldEntity target,
      DamageType type,
      int amount,
      int directionX,
      int directionY,
      AreaEffectEntity areaSource,
      AreaDamageType areaDamage)
      implements QueuedHit {

    TypedHit(
        WorldEntity source,
        WorldEntity target,
        DamageType type,
        int amount,
        int directionX,
        int directionY,
        AreaEffectEntity areaSource) {
      this(source, target, type, amount, directionX, directionY, areaSource, null);
    }
  }

  /**
   * One direct hit waiting for the drain: what the attacker's targeting visit hands the damage
   * entry, dealt as {@link #dealDamage(WorldEntity, TargetView, int, int, int)} deals it at once,
   * then followed by the attacker's buff on damage when the hit landed. A hit with no attacker, as
   * a damage run hands the entry one, is dealt the same way, with no buff on damage after it, and
   * kills for the side the run handed with it.
   *
   * @param attacker the entity whose hit it is, or null for none
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @param side the side a hit with no attacker kills for, or -1 for none; a hit with an attacker
   *     kills for the attacker's side
   */
  private record DirectHitDue(
      WorldEntity attacker, TargetView target, int damage, int directionX, int directionY, int side)
      implements QueuedHit {}

  /**
   * A reflected attack's damage waiting for the drain, queued by the reflect behind every hit
   * already waiting: dealt there as {@link #reflectedHitNow(ReflectedHitDue)} deals it.
   *
   * @param reflecting the reflecting unit, the hit's attacker
   * @param attacker what dealt the hit the reflect answered
   * @param source the character the reflect traced that hit to
   * @param struck the one struck back
   * @param hitSpeed the reflecting unit's hit-speed scale of 100 at the reflect
   * @param level the reflecting unit's level, packed
   * @param damage the reflected damage at that level
   * @param directionX the answered hit's direction along the arena's width
   * @param directionY the same along the length
   */
  private record ReflectedHitDue(
      WorldEntity reflecting,
      BattleEntity attacker,
      WorldEntity source,
      WorldEntity struck,
      int hitSpeed,
      int level,
      int damage,
      int directionX,
      int directionY)
      implements QueuedHit {}

  /**
   * One victim's share of a character's area damage waiting for the drain: dealt there as {@link
   * #dealAreaDamageNow(WorldEntity, WorldEntity, int, int)} deals it.
   *
   * @param attacker the entity whose hit made the area
   * @param victim the entity the area collected
   * @param damage hit points the area deals it, before its guards and the clamp to zero
   * @param hitId the id the hit carries
   */
  private record AreaHitDue(WorldEntity attacker, WorldEntity victim, int damage, int hitId)
      implements QueuedHit {}

  /**
   * A tether's hit on one object waiting for the drain: dealt there as {@link
   * #tetherHitNow(AreaEffectEntity, WorldEntity, int, int, int)} deals it.
   *
   * @param owner the area effect the tether runs on
   * @param victim the object hit
   * @param damage hit points the hit deals, before the object's guards and the clamp to zero
   * @param directionX the hit's direction along the width
   * @param directionY the hit's direction along the length
   */
  private record TetherHitDue(
      AreaEffectEntity owner, WorldEntity victim, int damage, int directionX, int directionY)
      implements QueuedHit {}

  /**
   * A carried push's hit on one object waiting for the drain: dealt there as a direct hit is dealt,
   * past the hidden test, with nothing after it.
   *
   * @param attacker the entity carrying the push
   * @param target the view of the object hit, as the hit was queued
   * @param damage hit points the hit deals, before the object's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  private record CarriedPushHitDue(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean noCounter)
      implements QueuedHit {}

  /**
   * One victim's share of a projectile's area impact waiting for the drain: dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it at once,
   * without a direction.
   *
   * @param projectile the projectile, standing at its aim
   * @param victim the entity the area collected
   * @param damage hit points the impact deals it, before its guards and the clamp to zero
   * @param hitId the id the impact carries
   */
  private record ProjectileAreaHitDue(
      ProjectileEntity projectile, WorldEntity victim, int damage, int hitId)
      implements QueuedHit {}

  /**
   * A projectile's hit on its one target waiting for the drain: dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it at once,
   * with the flight's direction.
   *
   * @param projectile the projectile, standing at its aim
   * @param target the entity the impact resolved against
   * @param damage hit points the impact deals, before the target's guards and the clamp to zero
   * @param hitId the id the impact carries
   * @param directionX direction of the flight along the arena's width
   * @param directionY direction of the flight along the arena's length
   */
  private record ProjectileHitDue(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY)
      implements QueuedHit {}

  /**
   * One hit of a buff's damage over time waiting for the drain: dealt there as {@link
   * #dealBuffDamageNow(BuffHitDue)} deals it, with its reflect, its observers and the death it
   * causes.
   *
   * @param target the entity carrying the buff
   * @param buff the instance whose damage it is
   * @param damage hit points it deals, after the carrier's own damage reduction
   * @param source what applied the buff, as the instance holds it when the hit is queued
   * @param side the side the buff was applied for, the killing side of a death it causes
   */
  private record BuffHitDue(
      WorldEntity target, BuffInstance buff, int damage, SpawnHost source, int side)
      implements QueuedHit {}

  /**
   * A capture's hit on a unit it holds waiting for the drain: dealt there as a direct hit is, past
   * the hidden test, with no direction and with the capturing character as the attacker, so a hit
   * that kills runs the unit's death there.
   *
   * @param owner the capturing character
   * @param unit the unit held
   * @param damage the damage per hit at the owner's level
   */
  private record CaptureHitDue(CharacterEntity owner, WorldEntity unit, int damage)
      implements QueuedHit {}

  /**
   * The kill of a fallen king's circle waiting for the drain, where it is dealt.
   *
   * @param target the entity the circle reached
   * @param radius the circle's radius
   * @param amount the target's hit points as the kill was queued
   */
  private record CircleKillDue(WorldEntity target, int radius, int amount) implements QueuedHit {}

  /**
   * A Kamikaze unit's kill of itself waiting for the drain, where it is dealt.
   *
   * @param unit the unit whose hit ended
   * @param amount the unit's hit points as the kill was queued
   */
  private record KamikazeKillDue(WorldEntity unit, int amount) implements QueuedHit {}

  /**
   * One step of a Kamikaze unit's drain over its time waiting for the drain, where it is dealt.
   *
   * @param unit the unit whose hit ended
   * @param damage the step
   */
  private record KamikazeDrainDue(WorldEntity unit, int damage) implements QueuedHit {}

  /**
   * A kill action's kill of its owner waiting for the drain, where it is dealt.
   *
   * @param target the kill action's owner
   * @param killer the entity that caused the action, or null for none
   * @param amount the target's hit points as the kill was queued
   */
  private record ActionKillDue(WorldEntity target, WorldEntity killer, int amount)
      implements QueuedHit {}

  /**
   * A tiebreaker's clearing's kill of a character waiting for the drain, where it is dealt.
   *
   * @param target the character the clearing reached
   * @param amount the character's hit points as the clearing reached it
   */
  private record ClearingKillDue(CharacterEntity target, int amount) implements QueuedHit {}

  /**
   * A hit the damage drain deals: a typed hit, a damage-taking action's hit, or a direct hit, a
   * share of a character's or a projectile's area, a tether's or a carried push's hit, a
   * projectile's hit on its one target, a hit of a buff's damage over time, a capture's hit, a
   * circle's kill, a Kamikaze unit's kill, a step of a Kamikaze unit's drain, a kill action's kill
   * or a tiebreaker's clearing's kill.
   */
  private sealed interface QueuedHit
      permits TypedHit,
          DirectHitDue,
          ReflectedHitDue,
          AreaHitDue,
          TetherHitDue,
          CarriedPushHitDue,
          ProjectileAreaHitDue,
          ProjectileHitDue,
          TravellingHitDue,
          ActionDamageDue,
          BuffHitDue,
          CaptureHitDue,
          CircleKillDue,
          KamikazeKillDue,
          KamikazeDrainDue,
          ActionKillDue,
          ClearingKillDue {}

  /**
   * A travelling hit of a projectile flying to a point waiting for the drain: dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it at once,
   * from the direction of the pass's centre, with nothing after it. A deflection's hit on the
   * deflector's parent goes to the same entry, without a direction, and is followed by the
   * projectile's after-damage target buff, as a queued impact is (see {@link
   * #drainProjectileBuff}): the deflection queues the hit with the projectile as its source, and
   * the drain buffs the victim of every hit it lets through whose source is a projectile.
   *
   * @param projectile the projectile whose body covered the entity
   * @param target the entity it covered
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param hitId the id the pass carries
   * @param directionX the entity's position less the pass's centre, along the width
   * @param directionY the same along the length
   * @param deflection true for a deflection's hit on the deflector's parent, which the projectile's
   *     after-damage target buff follows
   */
  private record TravellingHitDue(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY,
      boolean deflection)
      implements QueuedHit {}

  /**
   * A damage-taking action's hit waiting for the drain: its source, its target, the row's damage
   * and the added amount, with the direction from the source to the target as it was queued.
   *
   * @param source the entity that caused the action, or null for none
   * @param areaSource the area effect whose hit ran the action, or null for none; never set with a
   *     source
   * @param target the entity it lands on
   * @param damage the row's damage
   * @param added the added amount
   * @param directionX the target's position less the source's, along the width
   * @param directionY the same along the length
   */
  private record ActionDamageDue(
      WorldEntity source,
      AreaEffectEntity areaSource,
      ProjectileEntity projectileSource,
      WorldEntity target,
      TakeDamage.Damage damage,
      int added,
      int directionX,
      int directionY)
      implements QueuedHit {}

  /**
   * The hits dealt this tick that wait for the drain, in the order they were dealt: one queue, so a
   * direct hit and a typed hit land in the order they were dealt.
   *
   * <p>The game lands a direct hit's damage at the holder's damage drain, after every post-hook,
   * instead of inside the attacker's targeting visit: it queues the hit as it queues a typed hit,
   * so a unit the hit kills keeps its movement visit of that tick and dies where that visit left
   * it. A dash landing's hit on its one target goes to the same entry and is queued as a direct
   * hit: the dasher lands in its movement visit, so a unit the landing kills keeps its own movement
   * visit of that tick when it comes after the dasher's, and the units visited after the dasher
   * find it still moving.
   *
   * <p>The attacker's buff on damage follows its direct hit to the drain: the drain applies it
   * right after the hit's damage, once the damage entry has let the hit through, a hit that kills
   * included. That is after the tick's buff pass, so a 500 ms stun is first counted down on the
   * next tick and holds its target for all of its ten visits.
   *
   * <p>The same rule lands each victim's share of a character's area damage at the drain (see
   * {@link #dealAreaDamage(WorldEntity, WorldEntity, int, int)}): the area of a direct hit and of a
   * dash landing; no row sets a death damage. The area still pushes its victims at once, before
   * their damage lands, so a victim the area kills is pushed too.
   *
   * <p>It lands each victim's share of a projectile's area impact at the drain too (see {@link
   * #dealProjectileAreaDamage(ProjectileEntity, WorldEntity, int, int)}), pushed at once as well.
   * The share counts for the projectile's shooter, and the projectile's listening runs hear of it,
   * as the drain deals it.
   *
   * <p>It lands a projectile's hit on its one target at the drain as well (see {@link
   * #dealProjectileHit(ProjectileEntity, WorldEntity, int, int, int, int)}), whose guards are then
   * tested as the drain deals it: a dasher hit on the step its immunity runs out, after its dash,
   * has that immunity counted down by its own state visit of the step before the drain, and takes
   * the hit.
   *
   * <p>And it lands the two kills of a whole hit points at the drain: a fallen king's circle's (see
   * {@link #circleKill(WorldEntity, int)}) and a Kamikaze unit's of itself as its hit ends (see
   * {@link #kamikazeKill(WorldEntity)}). The circle runs in the match update, before the holder
   * tick, so an entity it kills is still alive through that tick's passes - a tower or a unit that
   * targets it still attacks it, and it takes its own visits - and dies at the drain. A Kamikaze
   * unit killed in its own hit is still alive for the entities visited after it in that tick: one
   * that targets it walks at it once more, and its body still stands among the others. A kill
   * action's kill of its owner (see {@link #kill(WorldEntity, WorldEntity)}) goes the same way: its
   * owner's kill action is scheduled at once, and the owner dies at the drain, so a building a
   * pending pass kills still pushes the units around it in that tick's movement pass. Each of these
   * kills - and a tiebreaker's clearing's (see {@link #clearingKill(CharacterEntity)}) - carries
   * the hit points its target had as it was queued, and the drain deals that amount through the
   * damage entry, which lowers it by the target's damage reduction: a target with a reduction
   * listed lives through it, and one healed since keeps the heal.
   *
   * <p>A buff's damage over time goes the same way (see {@link #dealBuffDamage(WorldEntity,
   * BuffInstance, int)}): the buff pass queues each hit it finds due, and the carrier takes it, and
   * dies of it, at the drain. So a carrier the damage kills is still alive for the rest of the buff
   * pass and for the post-hooks, and what a later carrier's buff makes in that pass - a spawner
   * buff's child - is made before what the dying carrier's death leaves.
   *
   * <p>Each step of a Kamikaze unit's drain over its time goes the same way (see {@link
   * #kamikazeDrain(WorldEntity, int)}): the unit's state visit queues it, and the step that takes
   * its last hit point kills it at the drain. So its death spawn is made after every state visit of
   * that tick, and a spawner listed after it that fires on that tick makes its child first.
   *
   * <p>A Goblinstein tether's hit (see {@link #tetherHit(AreaEffectEntity, WorldEntity, int, int,
   * int)}) and an evolved Battle Ram's carried push's hit (see {@link
   * #dealCarriedPushDamage(WorldEntity, WorldEntity, int, int, int)}) go the same way: each run, in
   * the run pass, hands its hit to the damage entry, so an object either kills is still alive for
   * the second pending pass and the post-hooks and dies at the drain, and its death action runs in
   * the third pending pass. A kill that death action hands on - the evolved Skeleton Army general's
   * kill of the spectral skeletons of its chain - is queued after the drain and lands at the next
   * tick's, as the recorded battles show.
   *
   * <p>The evolved Dart Goblin's poison damage goes the same way (see {@link
   * #dealQueuedDamage(TargetView, int, int, int, int)}): its run, in the action pass, hands each
   * hit to the damage entry with no attacker and the run's side, so a target the poison kills is
   * still alive for the post-hooks - a spawner's child made in them may still pick it - and dies at
   * the drain, for the run's side.
   */
  private final List<QueuedHit> queuedHits = new ArrayList<>();

  /**
   * Where each entity's queued total starts in {@link #queuedHits}: the number of hits queued when
   * its hit points' own visit, in the third component pass, emptied its total this tick. A hit
   * queued for it before that visit - a direct hit of a targeting visit, an area's share - is still
   * dealt at the drain, but no longer counted in the total a travelling hit asks about. Emptied
   * with the queue at the drain, which empties every total; an entity without an entry counts every
   * hit queued for it since.
   */
  private final Map<WorldEntity, Integer> queuedTotalStarts = new IdentityHashMap<>();

  /** The game tags the battle's expressions may name, by name, each with its index. */
  private final Map<String, Integer> gameTagIndex = new HashMap<>();

  /** The bits of each game tag, by index. */
  private final List<Long> gameTagMasks = new ArrayList<>();

  /** The tick the entity tick in progress belongs to, kept for the calls that are not handed it. */
  private int tick;

  public BattleWorld(TileMap tileMap) {
    this.tileMap = tileMap;
    this.grid =
        new CellGrid(
            tileMap,
            PathfindingGlobals.PATHFINDING_DYNAMIC_OCCLUSIONS,
            PathfindingGlobals.PATHFINDING_BUILDING_COST);
    this.index = new SpatialIndex(tileMap.width(), tileMap.height());
    // The game drops a unit's route when its pushback's flight ends, so its next walk searches a
    // fresh one from where the pushback left it.
    this.movementGlobals =
        MovementGlobals.forStandardArena(tileMap.width()).withPushbackEndDropsRoute(true);
    this.neighbourQuery = new IndexNeighbourQuery(index);
    this.holder = new EntityHolder(this);
  }

  /**
   * Makes a variable nameable in the battle's expressions. The data declares its variables; until
   * it is loaded they are registered here.
   *
   * @param name the name an expression uses
   * @param key the variable's key, which the actions that write it use
   */
  public void registerVariable(String name, int key) {
    variableKeys.put(name, key);
  }

  /** The key of a variable an expression may name, or null for a name that is none. */
  public Integer variableKey(String name) {
    return variableKeys.get(name);
  }

  /**
   * The value a variable reads as from an object no action has written it for: its row's
   * DefaultValue, or 0 for a row that sets none or a key no row declares.
   *
   * @param key the variable's key
   */
  public int variableStart(int key) {
    return variableStarts.getOrDefault(key, 0);
  }

  /** The global ids of the data rows the battle's expressions have named, in the order named. */
  private final List<Integer> dataRowIds = new ArrayList<>();

  /** Each named data row's place in {@link #dataRowIds}, by name. */
  private final Map<String, Integer> dataRowIndex = new HashMap<>();

  /**
   * The place of a character or building row an expression names, which its compiled call carries;
   * null for a name that is no such row, or while no data is loaded.
   *
   * @param name the row's name, matched exactly
   */
  Integer dataRow(String name) {
    Integer index = dataRowIndex.get(name);
    if (index != null || records == null) {
      return index;
    }
    Integer globalId = records.unitGlobalId(name);
    if (globalId == null) {
      return null;
    }
    dataRowIds.add(globalId);
    dataRowIndex.put(name, dataRowIds.size() - 1);
    return dataRowIds.size() - 1;
  }

  /** The global id of the data row at a place {@link #dataRow} gave. */
  int dataRowId(int index) {
    return dataRowIds.get(index);
  }

  /** The battle's unit, projectile and card records, from the tables it was loaded with. */
  @Getter private BattleRecords records;

  /** The published globals' numbers the battle has read, each on its first use. */
  private final Map<String, Integer> globalNumbers = new HashMap<>();

  /**
   * A published global's number, read from the records on its first use.
   *
   * @param name the global's name
   */
  public int globalNumber(String name) {
    return globalNumbers.computeIfAbsent(name, records::globalNumber);
  }

  /**
   * The largest damage reduction, in percent, a carrier's buffs may add up to either way: the
   * global PROTECTION_CAP_PERCENTAGE.
   */
  int protectionCapPercent() {
    return globalNumber("PROTECTION_CAP_PERCENTAGE");
  }

  /** The battle's action rows, from the tables it was loaded with. */
  @Getter private ActionRows actions;

  /**
   * The global under which the end of a Kamikaze unit's hit discarded for distance is skipped, so
   * the unit lives on: published by the data of game client 16.635.1 (set), not by the data
   * versions of client 16.402.17, whose hit always ends, discarded or not.
   */
  static final String SKIP_KAMIKAZE_ON_DISCARDED_HIT = "LOGIC_SKIP_KAMIKAZE_ON_DISCARDED_HIT";

  /**
   * True when the loaded tables publish {@link #SKIP_KAMIKAZE_ON_DISCARDED_HIT} set: a hit
   * discarded for distance then skips its end, which a Kamikaze unit destroys itself in. Tables
   * that do not publish it are refused as they are loaded.
   */
  @Getter private boolean skipKamikazeOnDiscardedHit;

  /**
   * Which bit of an entity's tag word each flag is, as the loaded game tags table numbers the tags;
   * every entity of the battle shares it. No flag has a bit until the tables are loaded.
   */
  @Getter private EntityFlags flagBits = EntityFlags.NONE;

  /** How many characters the battle has made, which a summon's name carries. */
  private int charactersMade;

  /** The battle's target locks, made by the first collector's step; null until then. */
  private TargetLocks locks;

  /**
   * Loads the game's tables into the battle: declares their variables and game tags and keeps the
   * records and action rows built from them, which the battle's entities build their actions from.
   * The battle models the rules of one game client, so tables of a data version that client has not
   * run are refused, whatever their rows: they would play under rules they were never run with.
   *
   * @param tables the game tables of one data version
   * @throws UnsupportedOperationException for tables of a data version the battle does not model
   * @throws IllegalArgumentException for tables without a global the battle reads, naming it
   */
  public void load(GameTables tables) {
    requireModelled(tables);
    declare(tables);
    this.flagBits = EntityFlags.of(tables);
    this.records = new BattleRecords(tables);
    this.actions = new ActionRows(tables, records);
    // The game reads both globals' rows with no test for a missing one, so tables without either
    // are refused, naming it, rather than read as a clear flag.
    this.movementGlobals =
        movementGlobals.withResetChargeDuringPushbackWhenMovementDisabled(
            records.globalBoolean(RESET_CHARGE_DURING_PUSHBACK));
    this.skipKamikazeOnDiscardedHit = records.globalBoolean(SKIP_KAMIKAZE_ON_DISCARDED_HIT);
  }

  /**
   * Refuses tables whose data version is not one the battle models: a data version game client
   * 16.635.1 has run ({@link GameVersions#CLIENT_16_635_1_DATA}).
   *
   * @param tables the game tables
   * @throws UnsupportedOperationException for any other data version
   */
  public static void requireModelled(GameTables tables) {
    if (!GameVersions.CLIENT_16_635_1_DATA.contains(tables.version())) {
      throw new UnsupportedOperationException(
          "the battle models the rules of game client "
              + GameVersions.CLIENT_16_635_1
              + ", which runs data versions "
              + new TreeSet<>(GameVersions.CLIENT_16_635_1_DATA)
              + "; it does not play tables of data version "
              + tables.version());
    }
  }

  /**
   * Declares every variable and game tag of the game's tables, so the battle's expressions may name
   * them and its actions write them: a variable keyed by its row's index, a game tag as the bit its
   * row's index gives in an entity's tag word.
   *
   * @param tables the game tables of one data version
   */
  public void declare(GameTables tables) {
    for (GameRow row : tables.table("variables").rows()) {
      // A row names its variable and may give it a start value; any other column it sets is
      // refused.
      for (String column : row.setColumns()) {
        if (!column.equals("Name") && !column.equals("DefaultValue")) {
          throw new UnsupportedOperationException(
              "the variables row " + row.name() + " sets " + column + ", which is not modelled");
        }
      }
      registerVariable(row.name(), row.index());
      // An expression reading the variable from an object that has no value for it reads the
      // row's DefaultValue (0 when unset), found by the variable's key.
      int start = row.intValue("DefaultValue");
      if (start != 0) {
        variableStarts.put(row.index(), start);
      }
    }
    for (GameRow row : tables.table("game_tags").rows()) {
      registerGameTag(row.name(), 1L << row.index());
    }
  }

  /**
   * What an action row built for an arena entity reads from it: its expressions compiled for it and
   * evaluated afresh each time, the battle's variable keys, its tag word, its spawn rate and what
   * its buffs make of a hit speed step.
   *
   * @param owner the entity the row is built for
   */
  public ActionBinding binding(WorldEntity owner) {
    BattleWorld world = this;
    return new ActionBinding() {
      @Override
      public IntSupplier expression(String text) {
        Expression expression =
            ExpressionCompiler.compile(text, new BattleExpressionEnvironment(owner, world));
        return () ->
            ExpressionEvaluator.evaluate(expression, new BattleExpressionEnvironment(owner, world));
      }

      @Override
      public int variableKey(String name) {
        return declaredVariable(name);
      }

      @Override
      public LongSupplier tags() {
        return () -> owner.getView().getFlags();
      }

      @Override
      public IntSupplier spawnRate() {
        return () -> owner.getBuffs().spawnRate();
      }

      @Override
      public IntUnaryOperator hitSpeed() {
        return base -> owner.getBuffs().hitSpeed(base);
      }
    };
  }

  /** The key of a declared variable; fails for a name the battle does not declare. */
  public int declaredVariable(String name) {
    Integer key = variableKeys.get(name);
    if (key == null) {
      throw new IllegalArgumentException("the battle declares no variable " + name);
    }
    return key;
  }

  /**
   * Makes a game tag nameable in the battle's expressions. The data declares its tags; until it is
   * loaded they are registered here, each taking the next index.
   *
   * @param name the name an expression uses
   * @param mask the tag's bits in an entity's tag word
   */
  public void registerGameTag(String name, long mask) {
    Integer index = gameTagIndex.get(name);
    if (index != null) {
      gameTagMasks.set(index, mask);
      return;
    }
    gameTagIndex.put(name, gameTagMasks.size());
    gameTagMasks.add(mask);
  }

  /** The index of a game tag an expression may name, or null for a name that is none. */
  Integer gameTagIndex(String name) {
    return gameTagIndex.get(name);
  }

  /**
   * Whether a character in the battle names a buff in its IgnoreTargetsWithBuff column without
   * ranking its carriers lower instead, so that its validator would pass over them; such a buff is
   * not modelled. A character that only ranks them lower is answered by its selection's priority.
   * Queued entities count too, as they will be live by the time anyone targets.
   *
   * @param buff the buff row's name
   */
  boolean passedOverBySomeone(String buff) {
    for (WorldEntity entity : present()) {
      if (passesOver(entity, buff)) {
        return true;
      }
    }
    for (BattleEntity entity : holder.queued()) {
      if (entity instanceof WorldEntity w && passesOver(w, buff)) {
        return true;
      }
    }
    return false;
  }

  private static boolean passesOver(WorldEntity entity, String buff) {
    return buff.equals(entity.getData().ignoreTargetsWithBuff())
        && !entity.getData().deprioritizeTargetsWithBuff();
  }

  /** The bits of the game tag of the given index. */
  long gameTagMask(int index) {
    return gameTagMasks.get(index);
  }

  public List<WorldEntity> present() {
    return List.copyOf(present);
  }

  /**
   * The arena entities of the last pre-pass that are still in the battle: the pre-pass list without
   * the ones a cleanup has removed since it was built.
   */
  List<WorldEntity> presentListed() {
    List<WorldEntity> listed = new ArrayList<>(present.size());
    for (WorldEntity entity : present) {
      if (known.containsKey(entity.getView())) {
        listed.add(entity);
      }
    }
    return listed;
  }

  /** This tick's projectiles in ascending id, the ones the tick visits. */
  public List<ProjectileEntity> projectiles() {
    return List.copyOf(projectiles);
  }

  /** The arena entity behind a view, or null for one that has left the battle. */
  public WorldEntity entityOf(GridEntity view) {
    return known.get(view);
  }

  /** The id of the next hit, counted from one. */
  public int nextHitId() {
    return ++hitCounter;
  }

  /**
   * The group id of the next projectile that shares one with the projectiles its impact spawns,
   * counted from one by a counter of its own.
   */
  public int nextProjectileGroupId() {
    return ++projectileGroupCounter;
  }

  /** Attaches an observer; it is told about every tick and every hit from the next one on. */
  public void addObserver(WorldObserver observer) {
    events.add(observer);
    championViewsWanted |= observer.wantsChampionViews();
  }

  /**
   * Deals the damage of one hit with no attacker to the entity behind a target view, and tells
   * every observer what it did. An entity that has left the battle takes nothing.
   *
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealDamage(TargetView target, int damage, int directionX, int directionY) {
    return dealDamage(null, target, damage, directionX, directionY);
  }

  /**
   * Deals the damage of one entity's direct hit to the entity behind a target view, and tells every
   * observer what it did. An entity that has left the battle takes nothing.
   *
   * @param attacker the entity whose hit it is, or null for none
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealDamage(
      WorldEntity attacker, TargetView target, int damage, int directionX, int directionY) {
    return dealDamage(attacker, target, damage, directionX, directionY, false);
  }

  /**
   * Deals the damage of one entity's direct hit, as its targeting visit's hit application hands it
   * to the damage entry, or of a dash landing's hit on its one target, which the landing hands to
   * the same entry: queued for the damage drain, in the order hits are dealt (see {@link
   * #queuedHits}), where it is dealt as {@link #dealDamage(WorldEntity, TargetView, int, int, int)}
   * deals it. A hit dealt after the tick's drain waits for the next tick's, as a typed hit does.
   *
   * @param attacker the entity whose hit it is
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  public void dealDirectHit(
      WorldEntity attacker, TargetView target, int damage, int directionX, int directionY) {
    queuedHits.add(new DirectHitDue(attacker, target, damage, directionX, directionY, -1));
  }

  /**
   * Empties an entity's queued total, as its hit points' own visit does first, in the third
   * component pass, in every state: from here on only the hits queued for it after this point count
   * against its hit points when a travelling hit asks whether the queued damage kills it (see
   * {@link #queuedKill}). The hits already queued stay in the queue and are dealt at the drain.
   *
   * @param entity the entity whose hit points take their visit
   */
  public void emptyQueuedTotal(WorldEntity entity) {
    queuedTotalStarts.put(entity, queuedHits.size());
  }

  /**
   * Deals the damage of one hit with no attacker, as a damage run hands it to the damage entry with
   * the side it deals it for: queued for the damage drain, in the order hits are dealt (see {@link
   * #queuedHits}), where it is dealt as {@link #dealDamage(TargetView, int, int, int)} deals it at
   * once. The record keeps the side, which the drain hands the death handler with no attacker: a
   * target the hit kills dies for that side, so its killed action and the elixir its death gives
   * the opponent go as for a kill of that side (see {@link #entityDied}). A hit dealt after the
   * tick's drain waits for the next tick's.
   *
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @param side the side the hit is dealt for, or -1 for none
   */
  public void dealQueuedDamage(
      TargetView target, int damage, int directionX, int directionY, int side) {
    queuedHits.add(new DirectHitDue(null, target, damage, directionX, directionY, side));
  }

  /**
   * Deals the damage of a carried push's hit to an entity, as the push's run hands it to the damage
   * entry: queued for the damage drain, in the order hits are dealt (see {@link #queuedHits}),
   * where it is dealt as {@link #dealDamage(WorldEntity, TargetView, int, int, int)} deals a direct
   * hit's, but past the hidden test. So an entity the push kills dies at the drain, after the run
   * pass and the post-hooks, and its death action runs in the tick's last pending pass.
   *
   * @param attacker the entity carrying the push
   * @param target the entity hit
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  public void dealCarriedPushDamage(
      WorldEntity attacker,
      WorldEntity target,
      int damage,
      int directionX,
      int directionY,
      boolean noCounter) {
    queuedHits.add(
        new CarriedPushHitDue(
            attacker, target.getTargetView(), damage, directionX, directionY, noCounter));
  }

  private DamageResult dealDamage(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean passesHidden) {
    return dealDamage(attacker, target, damage, directionX, directionY, passesHidden, -1);
  }

  /**
   * Deals one hit as {@link #dealDamage(WorldEntity, TargetView, int, int, int)} does, with the
   * side a hit with no attacker kills for.
   *
   * @param side the side the hit kills for when it has no attacker, or -1 for none; a hit with an
   *     attacker kills for the attacker's side
   */
  private DamageResult dealDamage(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean passesHidden,
      int side) {
    return dealDamage(attacker, target, damage, directionX, directionY, passesHidden, side, false);
  }

  /**
   * Deals one hit as {@link #dealDamage(WorldEntity, TargetView, int, int, int, boolean, int)}
   * does, with the hit's NoCounter flag.
   *
   * @param noCounter true for a hit whose damage is flagged NoCounter, which no counter answers
   */
  private DamageResult dealDamage(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean passesHidden,
      int side,
      boolean noCounter) {
    WorldEntity entity = known.get(target.getEntity());
    if (entity == null) {
      return DamageResult.NOTHING;
    }
    int before = hitPointsOf(entity);
    DamageResult result =
        entity.takeDamage(
            damage, 0, directionX, directionY, passesHidden, attacker, attacker, null, noCounter);
    reflect(entity, attacker, before, result, directionX, directionY);
    events.damageDealt(entity, damage, result);
    if (result.died()) {
      // A hit with no attacker kills for the side it was dealt for, if any.
      entity.die(attacker, attacker != null ? attacker.side() : side);
    } else if (result.landed()) {
      referenceDrop(attacker, entity, false);
    }
    return result;
  }

  /** An entity's hit points as a hit reaches them, 0 for one without. */
  private static int hitPointsOf(WorldEntity entity) {
    return entity.getHitPoints() == null ? 0 : entity.getHitPoints().getHitPoints();
  }

  /** True while a reflect runs, inside which another is not modelled. */
  private boolean reflecting;

  /**
   * The reflect of a unit whose row sets a reflected-attack buff, the Electro Giant's, run inside a
   * hit that reached its hit points alive, after they took the hit and before its death runs.
   *
   * <p>Nothing happens while the unit is stunned - its own hit-speed scale of 100 below 1 - or for
   * a hit without an attacker. The attacker must be a character, building or tower; a projectile is
   * traced to its root, a character, unless its row ignores the reflect; an area effect and what it
   * launched are never struck back. A source riding on a parent is replaced by the parent. The one
   * struck must not be untouchable, must be of the other team, and must stand strictly inside the
   * reflect radius plus both collision radii. It takes the reflect's buff, at the unit's level and
   * for its side, on every reflected hit; and once per attack - the struck one's attack count and
   * id, so the shots of riders share their parent's key until the parent attacks itself, listed on
   * the unit and never cleared - the reflected damage at the unit's level, its crown tower column
   * against a crown tower. That damage is not dealt here: it is appended to the damage queue, as
   * the damage entry queues any hit, and the drain, which reads the queue's count again after every
   * hit, deals it in the same drain after every hit queued before it (see {@link
   * #reflectedHitNow}), through the damage entry with the unit as the attacker, the battle's holds
   * and the hidden test lifted. So a shot that lands on the attacker in the same step meets its
   * shield before the reflected damage does, a hit that kills the unit runs its death before the
   * one struck back takes the damage, and a child the struck one leaves as it dies is made after
   * both.
   */
  private void reflect(
      WorldEntity target,
      BattleEntity attacker,
      int hitPointsBefore,
      DamageResult result,
      int directionX,
      int directionY) {
    UnitData row = target.getData();
    if (row.reflectedAttackBuff() == null || !result.landed() || hitPointsBefore < 1) {
      return;
    }
    if (reflecting) {
      throw new UnsupportedOperationException(
          target.name() + " reflects inside another reflect, which is not modelled");
    }
    reflecting = true;
    try {
      reflectHit(target, attacker, directionX, directionY);
    } finally {
      reflecting = false;
    }
  }

  private void reflectHit(
      WorldEntity target, BattleEntity attacker, int directionX, int directionY) {
    UnitData row = target.getData();
    int hitSpeed = target.getBuffs().hitSpeed(REFLECT_SCALE_BASE);
    WorldEntity source = null;
    if (attacker instanceof WorldEntity character) {
      source = character;
    } else if (attacker instanceof ProjectileEntity projectile) {
      source = projectile.getRoot();
    }
    WorldEntity struck =
        source instanceof CharacterEntity rider && rider.getParent() != null
            ? rider.getParent()
            : source;
    Reflection none =
        new Reflection(target, attacker, source, struck, hitSpeed, null, 0, 0, 0, 0, 0);
    if (hitSpeed < 1
        || source == null
        || attacker instanceof ProjectileEntity projectile
            && projectile.getData().ignoreReflectedAttack()
        || struck.untouchable(true)
        || (struck.side() & 1) == (target.side() & 1)
        || !withinReflect(target, struck, row.reflectedAttackRadius())) {
      events.reflected(none);
      return;
    }
    if (struck.getData().reflectedAttackBuff() != null) {
      throw new UnsupportedOperationException(
          target.name()
              + " strikes back at "
              + struck.name()
              + ", which reflects too, not modelled");
    }
    int level = target.getPackedLevel();
    struck
        .getBuffs()
        .apply(
            buffData(row.reflectedAttackBuff()),
            row.reflectedAttackBuffDurationMs(),
            level,
            target,
            target.side());
    // The once-per-attack key is the struck one's, after the swap to the parent: a rider's shots
    // all count as its parent's current attack.
    if (struck.getHitPoints() == null || !target.reflectOnce(struck)) {
      events.reflected(
          new Reflection(
              target,
              attacker,
              source,
              struck,
              hitSpeed,
              row.reflectedAttackBuff(),
              row.reflectedAttackBuffDurationMs(),
              level,
              0,
              0,
              0));
      return;
    }
    int base =
        struck.getTargetView().isCrownTowerTarget()
            ? row.reflectAttackCrownTowerDamage()
            : row.reflectedAttackDamage();
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(), base, level, ScalingMode.CARD_DAMAGE, row.rarity());
    // The reflected damage is a hit of its own, put at the end of the damage queue: the drain
    // deals it after every hit queued before it, those of this tick still waiting among them.
    queuedHits.add(
        new ReflectedHitDue(
            target, attacker, source, struck, hitSpeed, level, damage, directionX, directionY));
  }

  /**
   * Deals a reflected attack's damage at the drain: through the damage entry with the reflecting
   * unit as the attacker (see {@link WorldEntity#takeReflectedDamage}), the observers told, and the
   * death of the one struck, for the reflecting unit, when the hit kills it. The reflecting unit
   * may have died since the reflect queued the hit; the hit is dealt all the same.
   */
  private void reflectedHitNow(ReflectedHitDue due) {
    WorldEntity target = due.reflecting();
    WorldEntity struck = due.struck();
    UnitData row = target.getData();
    int before = struck.getHitPoints().getHitPoints();
    DamageResult hit =
        struck.takeReflectedDamage(target, due.damage(), due.directionX(), due.directionY());
    events.reflectedHit(target, struck, due.damage(), hit);
    events.reflected(
        new Reflection(
            target,
            due.attacker(),
            due.source(),
            struck,
            due.hitSpeed(),
            row.reflectedAttackBuff(),
            row.reflectedAttackBuffDurationMs(),
            due.level(),
            due.damage(),
            before,
            struck.getHitPoints().getHitPoints()));
    if (hit.died()) {
      struck.die(target);
    }
  }

  /** The base the reflect scales its unit's hit speed from, which below 1 means a stun. */
  private static final int REFLECT_SCALE_BASE = 100;

  /**
   * Whether the one struck stands strictly inside the reflect radius plus both collision radii, the
   * squares compared as the game compares them: unsigned, in 32 bits.
   */
  private static boolean withinReflect(WorldEntity target, WorldEntity struck, int radius) {
    int dx = target.getView().getX() - struck.getView().getX();
    int dy = target.getView().getY() - struck.getView().getY();
    int reach = radius + target.getData().collisionRadius() + struck.getData().collisionRadius();
    return Integer.compareUnsigned(dx * dx + dy * dy, reach * reach) < 0;
  }

  /**
   * Whether a hooking projectile has lost its target, which ends it: the projectile's visit ends it
   * as it ends one that lost its owner, without a step, a release where it stands or an impact,
   * once its target has left the battle or was let go (as a deflection lets it go), or has hit
   * points and none are left, as a destroyed crown tower that stays in the battle has none.
   *
   * @param projectile the hooking projectile
   */
  public boolean hookTargetLost(ProjectileEntity projectile) {
    WorldEntity target = projectile.getTarget();
    return target == null || !target.getTargetView().alive();
  }

  /**
   * The end of a hooking projectile that lost its owner or its target: an owner that waits for the
   * pull (its components off while the hook brings the target back) is asked to resume at once, as
   * its state's own end would ask it.
   *
   * @param projectile the hooking projectile
   */
  public void hookEnded(ProjectileEntity projectile) {
    WorldEntity owner = projectile.getOwner();
    if (owner instanceof CharacterEntity waiting
        && owner.getView().getState() == GridEntityState.COMPONENTS_DISABLED) {
      waiting.resume();
    }
  }

  /**
   * Whether a hooking projectile has lost its owner: the owner has left the battle, or its
   * targeting component is off, as a death or a stun switches it.
   *
   * @param projectile the hooking projectile
   */
  public boolean ownerLost(ProjectileEntity projectile) {
    WorldEntity owner = projectile.getOwner();
    return owner == null || !owner.isActive(CharacterEntity.TARGETING_SLOT);
  }

  /**
   * Whether a hooking projectile's target keeps out of the hook on this step: a character running a
   * dash that the untouchable test passes by (a dash under a row with a dash immunity, the immunity
   * left after a dash, a tunnel or a parent). The projectile flies on at it all the same; an
   * arrival on such a step does not hook but ends as an ordinary arrival, at the aim, with its
   * impact. A dasher the test does not pass by, as a jumping Mega Knight, is hooked as any other
   * target.
   *
   * @param projectile the hooking projectile
   */
  public boolean hookKeptOff(ProjectileEntity projectile) {
    return projectile.getTarget() instanceof CharacterEntity target
        && (target.getView().getFlags() & target.getView().getFlagBits().dashing()) != 0
        && target.untouchable(true);
  }

  /**
   * Whether someone else holds a lock on a hooking projectile's target: the battle's locks, made by
   * a collector's first step (a capture, a net) or held by a unit on itself through its ability
   * (the Boss Bandit's, a rerolling Barbarian's), are asked about the owner and the target on
   * channel 0. Asked on every step of the flight; only the arrival and the hooked step read the
   * answer.
   *
   * @param projectile the hooking projectile
   */
  public boolean hookTargetHeld(ProjectileEntity projectile) {
    WorldEntity target = projectile.getTarget();
    return locks != null
        && projectile.getOwner() != null
        && target != null
        && locks.heldByOther(projectile.getOwner().getId(), target.getId(), 0);
  }

  /**
   * Refuses a hooked projectile whose target someone else now holds a lock on: the hook lets the
   * target go at once, the target's following ended and the projectile released, which no reference
   * holds. A hook arriving at a held target does not hook it (an ordinary arrival); this is the
   * step after a hook, the target held only once pulled.
   *
   * @param projectile the hooked projectile
   */
  public void refuseLockedHook(ProjectileEntity projectile) {
    WorldEntity target = projectile.getTarget();
    throw new UnsupportedOperationException(
        projectile.name()
            + " hooked "
            + target.name()
            + ", which another now holds, letting it go not modelled");
  }

  /**
   * Refuses a hook arriving at a target someone else held a lock on during its flight but no longer
   * does: the lock is asked on each step and only the arrival's answer counts, so the hook would
   * take the target, but no reference holds such an arrival.
   *
   * @param projectile the hooking projectile
   */
  public void refuseHookAfterLock(ProjectileEntity projectile) {
    throw new UnsupportedOperationException(
        projectile.name()
            + " hooks "
            + projectile.getTarget().name()
            + ", which another held during its flight and no longer does, not modelled");
  }

  /**
   * A hooking projectile asks a unit's setter for a state: its target to be pulled, its owner to
   * wait or to be dragged, and its owner to move on once dragged; every observer is told.
   *
   * @param projectile the hooking projectile
   * @param unit its target or its owner
   * @param state the state asked for
   */
  public void hookRequest(ProjectileEntity projectile, WorldEntity unit, int state) {
    // Only a character is asked: a building or a crown tower it hooked drags the owner instead.
    int old = unit.getView().getState();
    ((CharacterEntity) unit).requestState(state);
    events.dragStateSet(projectile, unit, old, state);
  }

  /**
   * Has a unit follow a hooking projectile: its target while it is pulled, its owner while the hook
   * on a building drags it.
   *
   * @param unit the unit
   * @param projectile the hooking projectile
   */
  public void follow(WorldEntity unit, ProjectileEntity projectile) {
    ((CharacterEntity) unit).follow(projectile);
  }

  /**
   * The hook's end of a jump or a dash with a height on the target it pulls, before it is pulled: a
   * target with a movement component in a jump, or dashing under a row with a jump height (a
   * jumping Mega Knight), is put down on the ground, asked to stand, which ends its dash, and its
   * route emptied. Any other is left alone.
   *
   * @param projectile the hooking projectile
   * @param target the target it hooked
   */
  public void putDown(ProjectileEntity projectile, WorldEntity target) {
    if (!(target instanceof CharacterEntity character) || !character.hasMovementComponent()) {
      return;
    }
    if (inTheAir(target)) {
      target.getView().setZ(0);
      hookRequest(projectile, target, GridEntityState.STANDING);
      character.resetRoute();
    }
  }

  /**
   * Whether a unit is in a jump or dashing under a row with a jump height (a jumping Mega Knight),
   * the units the put-down of a hook, of a capture's completed drag or of a knock's start sets on
   * the ground.
   */
  static boolean inTheAir(WorldEntity unit) {
    int state = unit.getView().getState();
    return state == GridEntityState.JUMPING
        || (state == GridEntityState.DASHING && unit.getData().jumpHeight() >= 1);
  }

  /**
   * Moves the owner a hook on a building drags, on the ground.
   *
   * @param owner the owner
   * @param x the new position along the width
   * @param y the new position along the length
   */
  public void dragTo(WorldEntity owner, int x, int y) {
    GridEntity view = owner.getView();
    view.setX(x);
    view.setY(y);
    view.setZ(0);
  }

  /**
   * Hands a launched projectile to the holder, which gives it its id at once and admits it at the
   * next cleanup, tells every observer of the launch, and runs its registration pass.
   */
  public void launch(ProjectileEntity projectile) {
    holder.add(projectile);
    events.projectileLaunched(projectile);
    registrationPass(projectile);
  }

  /**
   * The pass a projectile runs as the holder registers it, after its launcher's running actions
   * have heard of it: one that flies to a point, unless it sweeps out and back, hits what its body
   * covers where it starts at once, its body widened by the row's start radius, whatever delay it
   * waits before it flies.
   */
  private void registrationPass(ProjectileEntity projectile) {
    registrationPass(projectile, null);
  }

  /**
   * The registration pass of a projectile whose source is another projectile, the one whose impact
   * spawned it: that projectile's running actions hear of it in place of a launcher's.
   *
   * @param projectile the projectile being registered
   * @param spawner the projectile that spawned it, or null for one its launcher fired
   */
  private void registrationPass(ProjectileEntity projectile, ProjectileEntity spawner) {
    // Its source's running actions hear of it first: an enchanting buff may hand it a copy, and so
    // may the copy the projectile that spawned it carries.
    if (spawner != null) {
      spawner.projectileRegistered(projectile);
    } else if (projectile.getOwner() != null) {
      projectile.getOwner().projectileRegistered(projectile);
    }
    ProjectileData data = projectile.getData();
    if (data.homingLike() && data.pingpongVisualTimeMs() < 1) {
      cellPass(projectile, projectile.getX(), projectile.getY(), data.projectileStartExtraRadius());
    }
  }

  /**
   * The object with an id in the holder's live list, as the holder's id lookup finds it: an entity
   * killed during the tick is still listed until the closing cleanup. Null for none.
   */
  public BattleEntity liveObject(int id) {
    for (BattleEntity entity : holder.entities()) {
      if (entity.getId() == id) {
        return entity;
      }
    }
    return null;
  }

  /**
   * The object with an id in the holder's live list, or else among the objects handed to it and
   * waiting for the next cleanup; null for none.
   */
  BattleEntity liveOrQueued(int id) {
    BattleEntity live = liveObject(id);
    if (live != null) {
      return live;
    }
    for (BattleEntity entity : holder.queued()) {
      if (entity.getId() == id) {
        return entity;
      }
    }
    return null;
  }

  /** The battle's target locks, made by the first call, as a collector's first step makes them. */
  public TargetLocks locks() {
    if (locks == null) {
      locks = new TargetLocks();
    }
    return locks;
  }

  /**
   * Whether an object other than the owner holds a target lock on the target and channel, asked
   * without making the locks: false while no collector has made them.
   *
   * @param owner the id of the object asking
   * @param target the target's id
   * @param channel the lock channel
   */
  public boolean lockHeldByOther(int owner, int target, int channel) {
    return locks != null && locks.heldByOther(owner, target, channel);
  }

  /**
   * The object query around a point: the index's buckets over the circle, in their order, each
   * entity once, accepted on where it stands now - its centre closer than its collision radius plus
   * the radius - and by the filter, asked for the team and row name of the entity asking.
   *
   * @param asking the entity running the query
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the entities, in the query's order
   */
  public List<WorldEntity> objectQuery(WorldEntity asking, int radius, GameObjectFilter filter) {
    return objectQuery(asking, radius, filter, false);
  }

  /**
   * The object query around a given point, accepted as {@link #objectQuery(WorldEntity, int,
   * GameObjectFilter)} accepts around the asking entity's own, the filter asked for its team and
   * row name.
   *
   * @param asking the entity running the query
   * @param x the circle's centre along the width
   * @param y the circle's centre along the length
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the entities, in the query's order
   */
  public List<WorldEntity> objectQuery(
      WorldEntity asking, int x, int y, int radius, GameObjectFilter filter) {
    return objectQuery(x, y, asking.side(), asking.getData().name(), radius, filter, false);
  }

  /**
   * The object query around a point that tests a building by its square: as {@link
   * #objectQuery(WorldEntity, int, GameObjectFilter)}, but a building is accepted when the point
   * clamped into its square lies strictly within the radius, and anything else strictly within the
   * radius plus its collision radius.
   *
   * @param asking the entity running the query
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the entities, in the query's order
   */
  public List<WorldEntity> shapeQuery(WorldEntity asking, int radius, GameObjectFilter filter) {
    return objectQuery(asking, radius, filter, true);
  }

  /**
   * The object query around an area effect's point that tests a building by its square, as {@link
   * #shapeQuery} does for an entity, the filter asked for the area effect's team and row name.
   *
   * @param areaEffect the area effect running the query
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the entities, in the query's order
   */
  public List<WorldEntity> shapeQuery(
      AreaEffectEntity areaEffect, int radius, GameObjectFilter filter) {
    return objectQuery(
        areaEffect.getX(),
        areaEffect.getY(),
        areaEffect.side(),
        areaEffect.getData().name(),
        radius,
        filter,
        true);
  }

  private List<WorldEntity> objectQuery(
      WorldEntity asking, int radius, GameObjectFilter filter, boolean shapes) {
    GridEntity at = asking.getView();
    return objectQuery(
        at.getX(), at.getY(), asking.side(), asking.getData().name(), radius, filter, shapes);
  }

  private List<WorldEntity> objectQuery(
      int x,
      int y,
      int side,
      String askingName,
      int radius,
      GameObjectFilter filter,
      boolean shapes) {
    List<GridEntity> found = index.query(new SpatialQuery(x, y, radius, 0, false, shapes, 0, -1));
    List<WorldEntity> out = new ArrayList<>();
    if (found == null) {
      return out;
    }
    for (GridEntity view : found) {
      WorldEntity entity = entityOf(view);
      if (filter.matches(entity.filterSubject(), side & 1, askingName)) {
        out.add(entity);
      }
    }
    index.release(found);
    return out;
  }

  /**
   * The centre query of a chain attack's search: the index's buckets over the circle, in their
   * order, each entity once, accepted by the filter, asked for the team and row name of the entity
   * asking, and with its centre strictly within the radius, whatever its collision radius and
   * whether or not it is a building.
   *
   * @param asking the entity running the query
   * @param x the circle's centre along the width
   * @param y the circle's centre along the length
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the entities, in the query's order
   */
  public List<WorldEntity> centreQuery(
      WorldEntity asking, int x, int y, int radius, GameObjectFilter filter) {
    int side = asking.side() & 1;
    String name = asking.getData().name();
    List<GridEntity> found =
        index.centreQuery(
            x, y, radius, view -> filter.matches(entityOf(view).filterSubject(), side, name));
    List<WorldEntity> out = new ArrayList<>();
    if (found == null) {
      return out;
    }
    for (GridEntity view : found) {
      out.add(entityOf(view));
    }
    index.release(found);
    return out;
  }

  /**
   * Launches a chain attack's first hop at its target: from the launcher's own start, as a hit's
   * single projectile starts, at where the target stands now, at the launcher's level and on its
   * side, handed to the holder.
   *
   * @param launcher the entity running the chain attack
   * @param data the projectile's row
   * @param target the hop's target
   * @return the projectile
   */
  public ProjectileEntity launchChainHop(
      WorldEntity launcher, ProjectileData data, WorldEntity target) {
    ProjectileEntity projectile = new ProjectileEntity(this, data, launcher.side());
    ProjectileLauncher.launchAt(projectile, launcher, target);
    launch(projectile);
    return projectile;
  }

  /**
   * Launches a chain attack's later hop at its target: from the given start, at where the target
   * stands now, the launcher as launcher and owner, at its level and on its side, handed to the
   * holder.
   *
   * @param launcher the entity running the chain attack
   * @param data the projectile's row
   * @param target the hop's target
   * @param sx the start along the width
   * @param sy the start along the length
   * @param sz the start's height
   * @return the projectile
   */
  public ProjectileEntity launchChainHop(
      WorldEntity launcher, ProjectileData data, WorldEntity target, int sx, int sy, int sz) {
    ProjectileEntity projectile = new ProjectileEntity(this, data, launcher.side());
    ProjectileLauncher.launchThrown(projectile, launcher, target, sx, sy, sz);
    launch(projectile);
    return projectile;
  }

  /**
   * The projectile a hero Barbarian Barrel's reroll rolls its barbarian in, refused when its row
   * sets a column its flight does not model.
   *
   * @param action the reroll row's name
   * @param row the projectile's row
   * @return its columns
   */
  ProjectileData reRollProjectile(String action, String row) {
    ProjectileData data = records.projectile(row);
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          action
              + " rolls in "
              + row
              + ", which sets columns its flight does not model: "
              + data.unmodelledColumns());
    }
    return data;
  }

  /**
   * Launches a projectile a unit's action fires at a point: from the given start, with no target,
   * the unit as launcher and owner, at its level re-based on the row's rarity, carrying the unit's
   * play, handed to the holder, which admits it at the next cleanup.
   *
   * @param unit the unit
   * @param data the projectile's row
   * @param sx start position along the arena's width
   * @param sy start position along the arena's length
   * @param sz start height
   * @param hx the point it is fired at along the width
   * @param hy the point it is fired at along the length
   * @return the projectile
   */
  ProjectileEntity launchFromUnit(
      CharacterEntity unit, ProjectileData data, int sx, int sy, int sz, int hx, int hy) {
    ProjectileEntity projectile = new ProjectileEntity(this, data, unit.side());
    projectile.launchFromUnit(unit, unit.getDeployIndex(), sx, sy, sz, hx, hy);
    launch(projectile);
    return projectile;
  }

  /**
   * Launches a collector's projectile at a friend: from the launcher's own start, at where the
   * friend stands now, at the launcher's level and on its side, handed to the holder.
   *
   * @param launcher the collecting unit
   * @param data the projectile's row
   * @param friend the friend it flies to
   */
  public void launchAt(WorldEntity launcher, ProjectileData data, WorldEntity friend) {
    ProjectileEntity projectile = new ProjectileEntity(this, data, launcher.side());
    ProjectileLauncher.launchAt(projectile, launcher, friend);
    launch(projectile);
  }

  /**
   * Schedules a projectile's on-hit action on its target as its impact reaches it, the projectile
   * as the cause: from the post-hooks, so it waits for the phase-3 pending pass.
   *
   * @param projectile the landing projectile
   * @param target its target
   */
  public void onHitTarget(ProjectileEntity projectile, WorldEntity target) {
    BattleAction action = actions.build(projectile.getData().onHitTargetAction(), binding(target));
    target
        .actionHolder()
        .schedule(action, ActionHolder.OWN_DELAY, false, projectile.actionHolder());
  }

  /**
   * Kills an entity, as a kill action kills its owner, and tells every observer what the kill did
   * as they are told of a hit of its whole hit points. The kill is queued for the damage drain, and
   * the entity stays alive until the drain of the tick (see {@link #queuedHits}).
   *
   * @param target the entity
   * @param killer the entity that caused it, or null for none
   */
  public void kill(WorldEntity target, WorldEntity killer) {
    queuedHits.add(new ActionKillDue(target, killer, target.killAmount()));
  }

  /** A kill action's kill, dealt at the damage drain. */
  private void killNow(WorldEntity target, WorldEntity killer, int amount) {
    int before = target.getHitPoints() == null ? 0 : target.getHitPoints().getHitPoints();
    DamageResult result = target.takeKill(amount, null, killer);
    events.damageDealt(target, before, result);
    if (result.died()) {
      target.die(killer);
    }
  }

  /**
   * The push a unit makes on the enemies around it as it enters the deploying state through its
   * setter. Its query is the object query of the spatial index around the unit, as wide as its
   * SpawnPushback; each found entity of another side - its side as it is, not its team - that is
   * alive is tested: a flying one is passed by unless the unit attacks both air and ground, a
   * hidden one is passed by, and one without an active movement component, which every building is,
   * is left. Each other one is asked for a pushback of SpawnPushbackRadius away from the unit, with
   * the gates lifted: its row's IgnorePushback, a buff and the no-pushback flag do not refuse it,
   * and a pushback in flight or its hiding still does. Its mass is not read.
   *
   * <p>A card play enters the state in the command pass, while the index is empty, so the query
   * finds nobody.
   *
   * @param unit the unit
   */
  void spawnPush(CharacterEntity unit) {
    UnitData data = unit.getData();
    int x = unit.getView().getX();
    int y = unit.getView().getY();
    int radius = data.spawnPushback();
    int distance = data.spawnPushbackRadius();
    // The ground bit is the row's AttacksGround for one that attacks air, else set.
    boolean ground = !data.attacksAir() || data.attacksGround();
    boolean air = data.attacksAir();
    List<WorldEntity> found = new ArrayList<>();
    List<WorldEntity> pushed = new ArrayList<>();
    List<GridEntity> views = index.query(new SpatialQuery(x, y, radius, 0, false, false, 0, -1));
    if (views != null) {
      for (GridEntity view : views) {
        found.add(entityOf(view));
      }
      index.release(views);
    }
    for (WorldEntity entity : found) {
      if (!entity.getView().isAlive() || entity.side() == unit.side()) {
        continue;
      }
      if (entity.getData().air() && !(air && ground)) {
        continue;
      }
      if (entity.hidden()) {
        continue;
      }
      if (!(entity instanceof CharacterEntity character)
          || !character.isActive(CharacterEntity.MOVEMENT_SLOT)) {
        continue;
      }
      character.pushedOnDeploy(x, y, distance);
      pushed.add(character);
    }
    events.deployPushed(unit, radius, distance, found, pushed);
  }

  /**
   * The area effect a unit's direct hit makes, once its damage was dealt to its one target: at the
   * unit's point, not the target's, for its side and at its level, handed to the holder, which
   * gives it its id at once and admits it at the tick's closing cleanup, so it first updates on the
   * next tick. An area hit makes none.
   *
   * @param unit the unit whose hit it is
   */
  void areaEffectOnHit(WorldEntity unit) {
    createAreaEffect(
        unit.getData().areaEffectOnHit(),
        unit.getView().getX(),
        unit.getView().getY(),
        unit.side(),
        unit.getPackedLevel(),
        null,
        "area_effect_on_hit",
        unit.name());
  }

  /**
   * Morphs a unit that has surfaced into its row's morph, as the arrival of a tunnel does: the new
   * object is made on the unit's point with its level, lane and share of its hit points, a building
   * facing as the unit did; it is queued with its registration visit in the state it is made in,
   * then set to the unit's deploying state through its setter. The damage on its way to the unit
   * passes to the new object. The unit leaves at the closing cleanup.
   *
   * <p>A morph hands the new object to the removal notice of every entity that referenced the old
   * one, whose replacement path clears a continuous-damage attacker's ramp; that path is not
   * established, so a morph of a unit such an attacker references is refused.
   *
   * <p>Like any object the holder admits, the new object is started at the closing cleanup's fold:
   * its row's starting action - the Goblin Drill's damage area, or the evolved Goblin Drill's
   * relocation - is scheduled then, and runs in its first pending pass of the next tick.
   *
   * @param old the unit that surfaced
   */
  void morph(CharacterEntity old) {
    UnitData data = spawnedRow(old.getData().spawnPathfindMorph());
    for (WorldEntity entity : present) {
      if (entity.getData().attackSequence().mode() != AttackSequence.MODE_NONE
          && entity.getTargeting().getReference() == old.getTargetView()) {
        throw new UnsupportedOperationException(
            entity.name()
                + " is a continuous-damage attacker referencing "
                + old.name()
                + " as it morphs, whose replacement is not modelled");
      }
    }
    CharacterEntity made = CharacterEntity.morphedFrom(old, data);
    made.startOnAdmission();
    holder.addRegistered(made);
    made.startDeployingAfterMorph();
    old.morphedAway();
    events.morphed(old, made);
  }

  /**
   * Kills a Kamikaze unit at the end of its hit: its whole hit points, with itself as the attacker
   * on its own side, so its death action runs alone. Every observer is told of the kill. The kill
   * is queued for the damage drain, and the unit stays alive until the drain of the tick (see
   * {@link #queuedHits}).
   *
   * @param unit the unit
   */
  public void kamikazeKill(WorldEntity unit) {
    queuedHits.add(new KamikazeKillDue(unit, unit.killAmount()));
  }

  /** The Kamikaze kill, dealt at the damage drain. */
  private void kamikazeKillNow(WorldEntity unit, int amount) {
    int before = unit.getHitPoints().getHitPoints();
    DamageResult result = unit.takeKill(amount, unit, unit);
    events.kamikazeKilled(unit, before, result);
    if (result.died()) {
      unit.die(unit);
    }
  }

  /**
   * Deals one step of a Kamikaze unit's drain over its time, with itself as the attacker on its own
   * side: refused where damage is forbidden, passing the battle's holds, so that a step that takes
   * the last hit point runs its death as its own killer. The step is queued for the damage drain,
   * as a buff's damage over time is, and the unit stays alive until the drain of the tick (see
   * {@link #queuedHits}).
   *
   * @param unit the unit
   * @param damage the step
   */
  void kamikazeDrain(WorldEntity unit, int damage) {
    queuedHits.add(new KamikazeDrainDue(unit, damage));
  }

  /**
   * The step of a Kamikaze unit's drain, dealt at the damage drain: every observer is told of it,
   * and a step that takes the last hit point runs the unit's death there, after every state visit
   * of the tick.
   */
  private void kamikazeDrainNow(WorldEntity unit, int damage) {
    int before = unit.getHitPoints().getHitPoints();
    DamageResult result = unit.takeKamikazeDrain(damage);
    events.kamikazeDrained(unit, damage, before, result);
    if (result.died()) {
      unit.die(unit);
    }
  }

  /**
   * Kills an entity of a fallen king's side that the king's circle reached: its whole hit points,
   * no attacker, told to every observer as the circle's kill rather than a hit. The kill is queued
   * for the damage drain, and the entity stays alive until the drain of the tick (see {@link
   * #queuedHits}).
   *
   * @param target the entity
   * @param radius the circle's radius
   */
  public void circleKill(WorldEntity target, int radius) {
    queuedHits.add(new CircleKillDue(target, radius, target.killAmount()));
  }

  /** The circle's kill, dealt at the damage drain. */
  private void circleKillNow(WorldEntity target, int radius, int amount) {
    DamageResult result = target.takeKill(amount, null, null);
    events.circleKilled(target, radius);
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Kills a character of either side that a tiebreaker's clearing reached: it is resumed at once,
   * and its kill, its hit points as the clearing reached it with no attacker, is queued for the
   * damage drain, as a fallen king's circle's is. The character stays alive through the passes of
   * the update the clearing runs and dies at that update's drain (see {@link #queuedHits}), so
   * whatever reads it before - a tower aiming at it, its own movement, the place its death spawns
   * are made at - finds it as it was. The drain's damage entry lowers the kill by the character's
   * damage reduction, and a heal over time landing in that update's passes is not in it, so such a
   * character lives through the kill and the next step's clearing reaches it again.
   *
   * @param target the character
   */
  public void clearingKill(CharacterEntity target) {
    target.resume();
    queuedHits.add(new ClearingKillDue(target, target.killAmount()));
  }

  /**
   * The clearing's kill, dealt at the damage drain: the hit points it was queued with, no attacker,
   * told to every observer as the clearing's kill rather than a hit.
   */
  private void clearingKillNow(CharacterEntity target, int amount) {
    DamageResult result = target.takeKill(amount, null, null);
    events.clearingKilled(target);
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Removes an object a tiebreaker's clearing reached at once, outside any cleanup: a projectile,
   * an area effect, whose life is ended first, or a character without hit points. It leaves the
   * holder's live list as a cleanup's removal takes it, every listed entity told of it, so the
   * object listed after it moves into its place.
   *
   * @param entity the object
   */
  public void clearingRemoval(BattleEntity entity) {
    if (entity instanceof AreaEffectEntity areaEffect) {
      areaEffect.end();
    }
    holder.removeAtOnce(entity);
  }

  /**
   * Drains a tower by one step of a tiebreaker, which passes the battle's holds, with no attacker.
   *
   * @param target the tower
   * @param damage the step
   */
  public void drain(WorldEntity target, int damage) {
    DamageResult result = target.takeDrain(damage);
    events.drained(target, damage, target.getHitPoints().getHitPoints(), result.died());
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Deals one victim's share of the area of an entity's hit, and tells every observer what it did:
   * queued for the damage drain, in the order hits are dealt (see {@link #queuedHits}). A victim
   * that has left the battle takes nothing.
   *
   * @param attacker the entity whose hit made the area
   * @param victim the entity the area collected
   * @param damage hit points the area deals it, before its guards and the clamp to zero
   * @param hitId the id the hit carries
   * @return what the damage did to the victim; nothing yet for a share queued for the drain
   */
  public DamageResult dealAreaDamage(
      WorldEntity attacker, WorldEntity victim, int damage, int hitId) {
    queuedHits.add(new AreaHitDue(attacker, victim, damage, hitId));
    return DamageResult.NOTHING;
  }

  /**
   * Deals one victim's share of the area of an entity's hit at once, and tells every observer what
   * it did. A victim that has left the battle takes nothing.
   */
  private DamageResult dealAreaDamageNow(
      WorldEntity attacker, WorldEntity victim, int damage, int hitId) {
    if (victim == null || known.get(victim.getView()) != victim) {
      return DamageResult.NOTHING;
    }
    // A character's area carries no dedupe id and no direction.
    int before = hitPointsOf(victim);
    DamageResult result = victim.takeDamage(damage, 0, 0, 0, false, attacker, attacker);
    reflect(victim, attacker, before, result, 0, 0);
    events.areaHit(attacker, victim, damage, hitId, result);
    if (result.died()) {
      victim.die(attacker);
    } else if (result.landed()) {
      referenceDrop(attacker, victim, false);
    }
    return result;
  }

  /**
   * Deals one victim's share of a projectile's area impact: queued for the damage drain, in the
   * order hits are dealt (see {@link #queuedHits}), where it is dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it, without a
   * direction. Dealt at the drain, the share counts for the projectile's shooter as it is then, and
   * the projectile's listening runs hear of it there.
   *
   * @param projectile the projectile, standing at its aim
   * @param victim the entity the area collected
   * @param damage hit points the impact deals it, before its guards and the clamp to zero
   * @param hitId the id the impact carries
   * @return what the damage did to the victim; nothing yet for a share queued for the drain
   */
  public DamageResult dealProjectileAreaDamage(
      ProjectileEntity projectile, WorldEntity victim, int damage, int hitId) {
    queuedHits.add(new ProjectileAreaHitDue(projectile, victim, damage, hitId));
    return DamageResult.NOTHING;
  }

  /**
   * Deals the damage of a projectile's hit on its one target: queued for the damage drain, in the
   * order hits are dealt (see {@link #queuedHits}), where it is dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it. The
   * target's guards are tested as the drain deals it, after every post-hook of the tick: a dasher
   * whose immunity its own state visit of that tick counted down to 0 takes the hit.
   *
   * @param projectile the projectile, standing at its aim
   * @param target the entity the impact resolved against
   * @param damage hit points the impact deals, before the target's guards and the clamp to zero
   * @param hitId the id the impact carries, counted by the battle
   * @param directionX direction of the flight along the arena's width
   * @param directionY direction of the flight along the arena's length
   */
  public void dealProjectileHit(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY) {
    queuedHits.add(new ProjectileHitDue(projectile, target, damage, hitId, directionX, directionY));
  }

  /**
   * Deals the damage of a projectile's impact to its target, and tells every observer what it did.
   * A target that has left the battle takes nothing.
   *
   * @param projectile the projectile, standing at its aim
   * @param target the entity the impact resolved against
   * @param damage hit points the impact deals, before the target's guards and the clamp to zero
   * @param hitId the id the impact carries, counted by the battle
   * @param directionX direction of the flight along the arena's width
   * @param directionY direction of the flight along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealProjectileDamage(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY) {
    if (target == null || known.get(target.getView()) != target) {
      return DamageResult.NOTHING;
    }
    // A projectile's hit carries its group id as the dedupe id: 0 for one of no group, which lands
    // every time; the projectiles of one group land on the target once.
    int before = hitPointsOf(target);
    // The impact counts for the projectile's shooter, while it is in the battle.
    // A projectile with an action holder has its listening runs hear of the hit in the
    // subtraction.
    DamageResult result =
        target.takeDamage(
            damage,
            projectile.getGroupId(),
            directionX,
            directionY,
            false,
            projectile.getOwner(),
            projectile,
            projectile.hasActionHolder() ? () -> projectile.hitHeard(hitId, target) : null);
    reflect(target, projectile, before, result, directionX, directionY);
    events.projectileImpacted(projectile, target, damage, result);
    if (result.died()) {
      target.die(projectile);
    } else if (result.landed()) {
      referenceDrop(projectile, target, false);
    }
    return result;
  }

  /**
   * How many princess towers of a side are still in the battle. A destroyed one counts until the
   * cleanup that removes it.
   */
  public int princessTowerCount(int side) {
    int count = 0;
    for (WorldEntity entity : known.values()) {
      if (entity instanceof TowerEntity tower
          && tower.getData().summonerTower()
          && tower.side() == side) {
        count++;
      }
    }
    return count;
  }

  /**
   * The princess towers of a side still in the battle, in the order the holder lists them: the
   * side's list of map objects. A destroyed one is listed until the cleanup that removes it.
   */
  List<TowerEntity> princessTowers(int side) {
    List<TowerEntity> out = new ArrayList<>();
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof TowerEntity tower
          && tower.getData().summonerTower()
          && tower.side() == side) {
        out.add(tower);
      }
    }
    return out;
  }

  /** The kings' elixir in a match, which collectors and deaths pay into; null outside one. */
  @Getter @Setter private KingElixir kingElixir;

  /**
   * Whether a match has ended: from then every attack timer is held at zero and every ordinary hit
   * is refused. Never outside a match.
   */
  @Getter @Setter private boolean matchEnded;

  /**
   * Whether the battle holds every ordinary hit: from the first step of a match's tiebreaker. Never
   * outside a match.
   */
  @Getter @Setter private boolean hitsHeld;

  /**
   * What a king's post-hook runs first in a match: its hand refill and elixir; none outside one.
   */
  private Consumer<TowerEntity> kingVisit = king -> {};

  /**
   * Sets what a king's post-hook runs before its state visit: a match's refill and regeneration.
   *
   * @param kingVisit the visit, given the king
   */
  public void setKingVisit(Consumer<TowerEntity> kingVisit) {
    this.kingVisit = kingVisit;
  }

  /** Runs a king's match visit, at the head of its post-hook. */
  void kingVisit(TowerEntity king) {
    kingVisit.accept(king);
  }

  /**
   * The king tower of a side still in the battle, or null. It is looked up in the holder, where the
   * setup placed it, so a spell cast in the command pass of the first tick, before the world has
   * learnt its entities, finds it too.
   *
   * <p>A king tower is never removable and the tiebreaker's clearing leaves towers standing, so
   * once found it stays listed, and a king tower listed later has a higher id and comes after it:
   * the first one found for side 0 or 1 is remembered, a speed choice only.
   */
  public TowerEntity kingTower(int side) {
    boolean remembered = side == 0 || side == 1;
    if (remembered && kingTowers[side] != null) {
      return kingTowers[side];
    }
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof TowerEntity tower && tower.getData().king() && tower.side() == side) {
        if (remembered) {
          kingTowers[side] = tower;
        }
        return tower;
      }
    }
    return null;
  }

  /** Each side's king tower once {@link #kingTower(int)} has found it. */
  private final TowerEntity[] kingTowers = new TowerEntity[2];

  /**
   * Whether an asker is an area effect that reaches hidden units, which the validator lets take a
   * visible character even while it is hidden.
   *
   * @param asker the entity that asks, or null for none
   */
  boolean reachesHidden(GridEntity asker) {
    if (asker == null || asker.getType() != ReferenceValidator.TYPE_CONTACT) {
      return false;
    }
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof AreaEffectEntity area && area.asks(asker)) {
        return area.getData().affectsHidden();
      }
    }
    return false;
  }

  /**
   * The grid state of another character, or null for an entity that has none, such as a tower. The
   * movement pass asks this about its neighbours.
   */
  public GridUnitState unitStateOf(GridEntity view) {
    return known.get(view) instanceof CharacterEntity character ? character.getUnit() : null;
  }

  @Override
  public void prePass(int tick, List<BattleEntity> snapshot) {
    this.tick = tick;
    present.clear();
    views.clear();
    projectiles.clear();
    for (BattleEntity entity : snapshot) {
      if (entity instanceof WorldEntity worldEntity) {
        worldEntity.beginTick();
        present.add(worldEntity);
        views.add(worldEntity.getView());
      } else if (entity instanceof ProjectileEntity projectile) {
        projectiles.add(projectile);
      }
    }
    for (WorldEntity entity : present) {
      known.put(entity.getView(), entity);
    }
    // The entities the last pre-pass did not list: each other entity registered that one's whole
    // list then, so these are all it can still be missing.
    int pass = ++prePasses;
    arrivals.clear();
    for (int i = 0; i < present.size(); i++) {
      WorldEntity entity = present.get(i);
      if (entity.listedPass != pass - 1) {
        arrivals.add(entity);
      }
      entity.listedPass = pass;
    }
    for (int i = 0; i < present.size(); i++) {
      present.get(i).registerCandidates(present, arrivals, pass);
    }
    index.rebuild(views);
    deflection.indexDeflectors(snapshot);
    FootprintOverlay.buildOverlay(grid, views);
    grid.setChangeFlags(grid.getChanged().clone());
    // The target locks' step, before the entities' pre-hooks: a lock whose target or holder is no
    // longer listed alive goes, then the queued releases.
    if (locks != null) {
      locks.prePass(this::listedAlive);
    }
    // The observers share one copy of the list; with none, nothing is copied.
    if (events.listening()) {
      List<WorldEntity> snapshotOfPresent = present();
      events.afterPrePass(snapshotOfPresent);
    }
  }

  /**
   * The side lists' part of a removal: a removed entity leaves every arena entity's default targets
   * in the same cleanup, after each entity's own notice has run, the entities still waiting to be
   * admitted at that cleanup's fold included.
   */
  @Override
  public void entityRemoved(BattleEntity removed) {
    if (removed instanceof AreaEffectEntity areaEffect) {
      events.areaEffectRemoved(areaEffect);
      return;
    }
    if (!(removed instanceof WorldEntity gone)) {
      return;
    }
    known.remove(gone.getView());
    gone.leave();
    for (WorldEntity entity : known.values()) {
      entity.forget(gone.getView());
    }
    // The side lists are one list per side, read by every unit at its selection: a unit handed
    // over during this tick, which waits for this cleanup's fold and is not known yet, already
    // learnt this tick's entities at its registration and must lose the leaving one as well, or it
    // would keep a destroyed princess tower as a default target for the rest of the battle.
    for (BattleEntity waiting : holder.queued()) {
      if (waiting instanceof WorldEntity entity && !known.containsKey(entity.getView())) {
        entity.forget(gone.getView());
      }
    }
    events.entityRemoved(gone);
    // A child leaves its source's group as it is released, after every notice of the removal,
    // and a unit its card's group chain.
    if (gone instanceof CharacterEntity child) {
      CharacterEntity source = child.leaveGroup();
      if (source != null) {
        events.groupUnlinked(source, child);
      }
      if (child.leaveChain()) {
        events.chainUnlinked(child);
      }
    }
  }

  /**
   * The live list's objects as a game object filter asks about them, in the holder's order: an
   * arena entity as itself, and any other object by its kind and side alone, so a filter that lets
   * such an object through its gates is refused when it asks anything more.
   */
  List<FilterSubject> filterSubjects() {
    List<FilterSubject> subjects = new ArrayList<>();
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof WorldEntity arena) {
        subjects.add(arena.filterSubject());
      } else if (entity instanceof ProjectileEntity projectile) {
        subjects.add(new KindOnlySubject(FilterSubject.PROJECTILE, projectile.getSide() & 1));
      } else if (entity instanceof ActionOwnerEntity owner) {
        subjects.add(new KindOnlySubject(FilterSubject.AREA_EFFECT, owner.side() & 1));
      } else if (entity instanceof AreaEffectEntity areaEffect) {
        subjects.add(new KindOnlySubject(FilterSubject.AREA_EFFECT, areaEffect.side() & 1));
      } else {
        throw new UnsupportedOperationException(
            "a filter over " + entity.getClass().getSimpleName() + " is not modelled");
      }
    }
    return subjects;
  }

  /**
   * The live list's arena entities a game object filter lets through, asked for a team and a row
   * name, in the holder's order. Any other object is left out: none has hit points.
   *
   * @param filter the filter row
   * @param team the asking entity's team
   * @param rowName the asking entity's row name
   */
  List<WorldEntity> filteredEntities(GameObjectFilter filter, int team, String rowName) {
    List<WorldEntity> out = new ArrayList<>();
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof WorldEntity arena
          && filter.matches(arena.filterSubject(), team, rowName)) {
        out.add(arena);
      }
    }
    return out;
  }

  /**
   * The live list's objects a target resolver's Global shape collects and its filter lets through,
   * asked for a team and a row name, in the holder's order. Every object is offered, so a
   * projectile or an area effect the filter lets through is refused: no strategy's reading of one
   * is modelled.
   *
   * @param filter the resolver's filter
   * @param team the asking entity's team
   * @param rowName the asking entity's row name
   * @param action the name of the action that resolves
   */
  List<WorldEntity> resolverCandidates(
      GameObjectFilter filter, int team, String rowName, String action) {
    List<WorldEntity> out = new ArrayList<>();
    for (FilterSubject subject : filterSubjects()) {
      if (!(subject instanceof EntityFilterSubject entity)) {
        if (filter.matches(subject, team, rowName)) {
          throw new UnsupportedOperationException(
              action + " resolves an object other than a character or building, not modelled");
        }
        continue;
      }
      if (filter.matches(entity, team, rowName)) {
        out.add(entity.entity());
      }
    }
    return out;
  }

  /**
   * The live list's objects a target resolver's Global shape collects and its filter lets through,
   * asked by an entity: for its team and row name, the entity itself the one a MatchSelf filter
   * passes and the one whose buffs a buff checker looks for. In the holder's order; a projectile or
   * an area effect the filter lets through is refused, as above.
   *
   * @param filter the resolver's filter
   * @param team the asking entity's team
   * @param rowName the asking entity's row name
   * @param action the name of the action that resolves
   * @param asker the asking entity
   */
  List<WorldEntity> resolverCandidates(
      GameObjectFilter filter, int team, String rowName, String action, BattleEntity asker) {
    List<WorldEntity> out = new ArrayList<>();
    for (FilterSubject subject : filterSubjects()) {
      if (!(subject instanceof EntityFilterSubject entity)) {
        if (filter.matches(subject, team, rowName, false, asker.getId())) {
          throw new UnsupportedOperationException(
              action + " resolves an object other than a character or building, not modelled");
        }
        continue;
      }
      if (filter.matches(entity, team, rowName, entity.entity() == asker, asker.getId())) {
        out.add(entity.entity());
      }
    }
    return out;
  }

  /**
   * The objects a target resolver's Cone shape collects around a point, asked by an entity: the
   * object query around the point that tests a building by its square, in the index's bucket order,
   * x outer and y inner, each object once, that the filter lets through for the entity's team and
   * row name - the entity itself the one a MatchSelf filter passes and the one whose buffs a buff
   * checker looks for - and then those the cone keeps, pointed along its direction for the entity's
   * heading.
   *
   * @param x the point along the width
   * @param y the point along the length
   * @param heading the asking entity's heading in degrees, which the cone turns with if it says so
   * @param cone the resolver's shape
   * @param filter the resolver's filter
   * @param team the asking entity's team
   * @param rowName the asking entity's row name
   * @param asker the asking entity
   */
  List<WorldEntity> resolverConeCandidates(
      int x,
      int y,
      int heading,
      ConeShape cone,
      GameObjectFilter filter,
      int team,
      String rowName,
      BattleEntity asker) {
    List<GridEntity> found =
        index.query(new SpatialQuery(x, y, cone.radius(), 0, false, true, 0, -1));
    List<WorldEntity> out = new ArrayList<>();
    if (found == null) {
      return out;
    }
    int direction = cone.direction(heading);
    for (GridEntity view : found) {
      WorldEntity entity = entityOf(view);
      if (filter.matches(entity.filterSubject(), team, rowName, entity == asker, asker.getId())
          && cone.keeps(direction, view.getX() - x, view.getY() - y, view.getCollisionRadius())) {
        out.add(entity);
      }
    }
    index.release(found);
    return out;
  }

  /**
   * Sends a card play to every card-play listener on the live and the queued objects, in that
   * order: each hears it as its row says, and an activating play schedules the row's action on the
   * listener's owner, the owner its cause, to run in its next pending pass.
   *
   * @param side the side that played
   * @param deployed the card the play put down: a Mirror's repeated card, a variant's option
   * @param played the card played: the Mirror, the variant card
   * @param variant true for a variant card's play, whose cost a listener of its side that hears it
   *     and counts it refuses
   */
  void cardPlayed(int side, String deployed, String played, boolean variant) {
    List<BattleEntity> all = new ArrayList<>(holder.entities());
    all.addAll(holder.queued());
    for (BattleEntity entity : all) {
      if (!(entity instanceof WorldEntity owner)
          || !(entity.actions() instanceof ActionHolder listening)) {
        continue;
      }
      for (ActionInstance instance : List.copyOf(listening.running())) {
        if (!(instance instanceof CardDeployListener.Run run)) {
          continue;
        }
        // A variant card's play is tested as any play is: with EvaluateDeployedCard the option's
        // row it was cast as, else the variant card, each against its group's list for its form;
        // no shipped group lists either, so no listener hears one. The elixir a heard variant play
        // would add to a count below the listener's cost is not established.
        String scheduled =
            run.hear(
                owner.side(),
                side,
                deployed,
                played,
                () -> {
                  if (variant) {
                    throw new UnsupportedOperationException(
                        played
                            + " played as "
                            + deployed
                            + " is counted by "
                            + owner.name()
                            + "'s "
                            + instance.getAction().name()
                            + ", which listens for its side's card plays: the elixir a variant"
                            + " card's play adds to the count is not modelled");
                  }
                  return records.matchCard(deployed).cost();
                },
                card -> records.matchCard(card).form() == MatchCard.HERO_FORM);
        if (scheduled != null) {
          owner
              .actionHolder()
              .schedule(
                  actions.build(scheduled, binding(owner)),
                  ActionHolder.OWN_DELAY,
                  false,
                  owner.actionHolder());
        }
        events.cardPlayHeard(
            owner, instance.getAction().name(), side, played, deployed, run.getTotal(), scheduled);
      }
    }
  }

  /**
   * Makes the death area of a run of Goblinstein's ability at a point: for the area effect's side
   * and at its level, re-based on the death area's own rarity, the area effect its parent and
   * following nothing. It is handed to the holder inside the cleanup that removed the connected
   * unit, which admits it as it ends. It is a clone's when the area effect is.
   *
   * @param owner the area effect the run is on
   * @param row the death area's row
   * @param x the point along the width
   * @param y the point along the length
   * @return the death area
   */
  AreaEffectEntity goblinsteinDeathArea(AreaEffectEntity owner, String row, int x, int y) {
    AreaEffectEntity deathArea =
        createAreaEffect(
            row,
            x,
            y,
            owner.side(),
            owner.packedLevel(),
            null,
            "goblinstein_death",
            owner.name(),
            owner,
            null);
    // Made by the shared spawn, which asks the run's area effect whether it is a clone's.
    if (owner.isClone()) {
      deathArea.markClone();
    }
    return deathArea;
  }

  /**
   * Schedules a tether's activation row on the area effect or on the connected object, built for
   * that object, the area effect its cause; a row the tether does not name is nothing.
   *
   * @param owner the area effect the tether runs on
   * @param target the area effect itself, or the connected object
   * @param row the row, or null for none
   */
  void tetherActivation(AreaEffectEntity owner, BattleEntity target, String row) {
    if (row == null) {
      return;
    }
    ActionBinding binding;
    ActionHolder holder;
    if (target instanceof AreaEffectEntity area) {
      binding = area.binding();
      holder = area.actionHolder();
    } else {
      WorldEntity unit = (WorldEntity) target;
      binding = binding(unit);
      holder = unit.actionHolder();
    }
    holder.schedule(
        actions.build(row, binding), ActionHolder.OWN_DELAY, false, owner.actionHolder());
    events.tetherActivated(owner, target, row);
  }

  /**
   * The segment query of a tether's damage pass: the index's buckets over the segment's box widened
   * by the width, each object visited once, answered when the filter, asked for the area effect's
   * team and row name, takes it and it lies within the width of the segment where it stands now - a
   * building by its square, anything else by its circle.
   *
   * @param owner the area effect the tether runs on
   * @param ax the segment's start along the width
   * @param ay the segment's start along the length
   * @param bx the segment's end along the width
   * @param by the segment's end along the length
   * @param width how far either side of the segment it reaches
   * @param filter the targets filter, or null for none
   * @return the objects, in the query's order; null with no filter or no free result list
   */
  List<WorldEntity> segmentQuery(
      AreaEffectEntity owner, int ax, int ay, int bx, int by, int width, GameObjectFilter filter) {
    return segmentQuery(owner.side() & 1, owner.getData().name(), ax, ay, bx, by, width, filter);
  }

  /**
   * The segment query for an owner of the given team and row name, which the filter is asked for.
   *
   * @return the objects, in the query's order; null with no filter or no free result list
   */
  private List<WorldEntity> segmentQuery(
      int team, String name, int ax, int ay, int bx, int by, int width, GameObjectFilter filter) {
    if (filter == null) {
      return null;
    }
    List<GridEntity> found =
        index.segmentQuery(
            ax,
            ay,
            bx,
            by,
            width,
            view ->
                filter.matches(entityOf(view).filterSubject(), team, name)
                    && SegmentTests.withinSegment(view, ax, ay, bx, by, width));
    if (found == null) {
      return null;
    }
    List<WorldEntity> out = new ArrayList<>();
    for (GridEntity view : found) {
      out.add(entityOf(view));
    }
    index.release(found);
    return out;
  }

  /**
   * A tether's hit, as its damage pass hands it to the damage entry: queued for the damage drain,
   * in the order hits are dealt (see {@link #queuedHits}), where it is dealt as {@link
   * #tetherHitNow(AreaEffectEntity, WorldEntity, int, int, int)} deals it. So an object the tether
   * kills dies at the drain, after the run pass and the post-hooks, and its death action runs in
   * the tick's last pending pass: a kill that action hands on lands at the next tick's drain.
   *
   * @param owner the area effect the tether runs on
   * @param victim the object hit
   * @param damage the damage
   * @param directionX the hit's direction along the width
   * @param directionY the hit's direction along the length
   */
  void tetherHit(
      AreaEffectEntity owner, WorldEntity victim, int damage, int directionX, int directionY) {
    queuedHits.add(new TetherHitDue(owner, victim, damage, directionX, directionY));
  }

  /**
   * A tether's hit at the drain: an area effect's hit, the area effect the attacker, with the
   * damage entry's hidden test lifted and the hit's direction carried to a death.
   */
  private void tetherHitNow(
      AreaEffectEntity owner, WorldEntity victim, int damage, int directionX, int directionY) {
    int before = hitPointsOf(victim);
    DamageResult result = victim.takeDamage(damage, 0, directionX, directionY, true, null, owner);
    reflect(victim, owner, before, result, directionX, directionY);
    events.tetherHit(owner, victim, damage, directionX, directionY, result);
    if (result.died()) {
      victim.die(owner);
    }
  }

  /**
   * Schedules a tether's hit action on an object it reached, built for that object, the area effect
   * its cause.
   */
  void tetherHitAction(AreaEffectEntity owner, WorldEntity target, String row) {
    target
        .actionHolder()
        .schedule(
            actions.build(row, binding(target)),
            ActionHolder.OWN_DELAY,
            false,
            owner.actionHolder());
    events.tetherHitAction(owner, target, row);
  }

  /**
   * A card play of a match after its cast, told to both kings' champion slots, side 0's first: the
   * slot of the playing side that holds the champion the card summons follows the play.
   *
   * @param side the playing side
   * @param champion the champion the card summons, or null
   * @param index the play's deploy count
   * @param play the play's name
   */
  void championCardPlayed(int side, UnitData champion, int index, String play) {
    for (int kingSide = 0; kingSide < 2; kingSide++) {
      TowerEntity king = kingTower(kingSide);
      if (king != null) {
        king.championCardPlayed(side, champion, index, play);
      }
    }
  }

  /** A side's king's whole elixir, truncated. */
  int wholeElixir(int side) {
    return kingElixir.wholeElixir(side);
  }

  /**
   * Gives a champion slot's king back the cost of the slot's last use, as its last live copy left
   * inside the refund window: whole elixir up to the cap, the rest counted as wasted.
   */
  void championRefund(ChampionController slot, int mana) {
    int before = kingElixir.elixir(slot.side());
    kingElixir.add(slot.side(), mana * KingElixir.SCALE);
    events.championRefunded(slot, mana, before, kingElixir.elixir(slot.side()));
  }

  /**
   * Gives a unit's side's king back the cost of an undelivered ability use the unit carried as it
   * left the battle: whole elixir up to the cap, the rest counted as wasted, as the champion slot's
   * refund adds it.
   *
   * @param unit the leaving unit
   * @param mana the cost, in whole elixir
   */
  void undeliveredCostReturned(CharacterEntity unit, int mana) {
    int side = unit.side();
    int before = kingElixir.elixir(side);
    kingElixir.add(side, mana * KingElixir.SCALE);
    events.undeliveredCostReturned(unit, mana, before, kingElixir.elixir(side));
  }

  /**
   * What a champion slot reads of its side's characters of a champion row, in live-list order, for
   * the observers: before its step. Only for observers: an empty list, without the walk over every
   * entity, when none of them {@linkplain WorldObserver#wantsChampionViews() wants them}.
   */
  List<ChampionView> championViews(int side) {
    if (!championViewsWanted) {
      return List.of();
    }
    List<ChampionView> views = new ArrayList<>();
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof CharacterEntity unit
          && unit.side() == side
          && unit.getData().champion()) {
        views.add(
            new ChampionView(
                unit.name(),
                unit.getData().name(),
                unit.getDeployIndex(),
                unit.getView().getState(),
                unit.abilityPending(),
                unit.abilityWarningCountdown(),
                unit.getView().getFlags()
                    & (flagBits.abilityDisabled() | flagBits.abilityCooldownPaused()),
                unit.isClone()));
      }
    }
    return views;
  }

  /** The raw elixir of a side's king, in ten-thousandths. */
  int elixir(int side) {
    return kingElixir.elixir(side);
  }

  /**
   * A death: runs when a hit takes an arena entity's hit points to zero, in the pass that lands it,
   * once the entity has switched off what it no longer does.
   *
   * <p>The death slot comes first, inside the killing hit. Its death damage lands around the entity
   * - see {@link #deathDamage} - and then its death spawn stands on the ring around it or on its
   * point - see {@link #deathSpawn}. A death whose row sets a column of the slot the battle does
   * not model is refused: a second or third death spawn, a death projectile or area effect, a
   * starting buff taken back, and the parts of the death spawn's placement not established. The
   * elixir a death gives is left out, as the battle models no elixir.
   *
   * <p>Between the two, the killer's hook: an arena entity whose hit killed, and whose row has a
   * killed-done action, schedules it on itself - see {@link #killedDone}.
   *
   * <p>Then the death handler's hook: the death slot has scheduled the row's death action already,
   * the dying entity its own cause (see {@link #slotDeathAction}), so the handler schedules the
   * killed action alone (see {@link #killedHook}), built for the entity and scheduled on its own
   * holder with the row's own delay, what killed it as the cause: the unit for a direct hit or its
   * area, the projectile itself for an impact, the source of a typed hit, the killer of a kill. The
   * entity stays in the tick's snapshot until the closing cleanup, so a hook with no delay runs in
   * the next pending pass of the same tick: phase 2 after a hit in a component pass, phase 3 after
   * an impact or a typed hit, and at once after a kill inside a pending pass.
   *
   * <p>A buff's damage over time kills with the buff's source as the attacker, for the side the
   * buff was applied for (see {@link #dealBuffDamageNow}). A death with a killing side and no
   * attacker - the damage of a buff whose source has left the battle or never had one, or a hit of
   * the evolved Dart Goblin's poison run, which kills for the run's side - is handed in the game a
   * cause that carries that side alone and no object, which the battle does not hold. The battle
   * schedules the killed action with no cause when no action of its chain reads more of a cause
   * than its object (see {@link BattleAction#readsSideAloneCause}): the evolved Wall Breaker's
   * spawn, whose source is its owner, and the Boss Bandit's check of its killer's row, which finds
   * no object and runs nothing.
   *
   * <p>Refused rather than guessed: a killed action with no attacker whose chain reads the side,
   * level or point of its cause, a killed action whose attacker is an object of another kind than a
   * unit, a projectile or an area effect, and a death hook scheduled after the tick's last pending
   * pass, which would leave with the entity. The cause is the attacker's own holder, where the game
   * hands the hook a copy of the attacker made at the kill; a hook reads no more of it than its
   * presence and its row, which the copy shares.
   *
   * <p>After the hooks, the death handler's reward: in a match, a player's unit that gives elixir
   * on its death pays it to the king of the killing side, ten times its column in ten-thousandths;
   * a death with no killing side pays nothing.
   *
   * @param dying the entity that died
   * @param attacker what killed it: an arena entity, a projectile, or null for nothing
   * @param killingSide the side of the killing hit, or -1 for none
   */
  void entityDied(WorldEntity dying, BattleEntity attacker, int killingSide) {
    UnitData data = dying.getData();
    deathSlot(dying, data);
    killedDone(dying, attacker);
    killedHook(dying, attacker, data, killingSide);
    deathReward(dying, data, killingSide);
  }

  /**
   * The killer's hook, which the hit-points chain calls on the attacker of every hit, after the
   * dying entity's death slot and before its death handler: on a kill, an arena entity whose row
   * has a killed-done action schedules it on its own holder with the row's own delay, the entity it
   * killed as the cause. Outside a pending pass it runs in the killer's next pending pass of the
   * tick: phase 2 after a melee hit or a dash landing. A unit that killed itself is no attacker
   * here.
   *
   * <p>A projectile's kill is its shooter's: the hit-points chain hands the hook to the
   * projectile's launcher when that is a character, which schedules its own killed-done action the
   * same way, the entity killed its cause; a projectile without one runs none.
   *
   * <p>Refused rather than guessed: a kill after the tick's last pending pass, as for the death
   * hooks. Of the hook's other blocks, the evolved Pekka's TempResurrect hands a character's kill
   * to the battle's presentation listener and changes nothing the battle reads, so it has no part
   * here; a buff or a conversion on a kill read columns the battle refuses as it creates the unit.
   * Its first block, the reference a row that passes over buffed targets drops on every hit, comes
   * first - see {@link #referenceDrop}.
   *
   * @param dying the entity killed
   * @param attacker what killed it
   */
  private void killedDone(WorldEntity dying, BattleEntity attacker) {
    referenceDrop(attacker, dying, true);
    // A projectile's kill reaches its shooter: its launcher, when that is a character. With none
    // the projectile itself hears the kill, which runs no row.
    BattleEntity hook =
        attacker instanceof ProjectileEntity projectile ? projectile.getOwner() : attacker;
    if (!(hook instanceof WorldEntity killer) || killer == dying) {
      return;
    }
    String action = killer.getData().onKilledDoneAction();
    if (action == null) {
      return;
    }
    if (!holder.hasPendingPassAhead()) {
      throw new UnsupportedOperationException(
          killer.name()
              + " killed after the tick's last pending pass, where its killed-done action would"
              + " wait; such a kill is not established");
    }
    boolean inPendingPass = holder.isInPendingPass();
    events.killedDoneScheduled(killer, dying, action, inPendingPass);
    killer
        .actionHolder()
        .schedule(
            actions.build(action, binding(killer)),
            ActionHolder.OWN_DELAY,
            false,
            dying.actionHolder());
  }

  /**
   * The first block of the killer's hook, which the hit-points chain calls on the attacker of every
   * hit that lands, a kill or not: a character whose row both passes over and ranks lower the
   * targets carrying a buff - the Ram Rider's rider - drops its reference through the setter's null
   * path while its targeting component is on, before the kill's blocks, the death handler and a
   * projectile's target buff. It selects again on its next targeting visit, where the buff's
   * carriers rank lower; its attack time runs on. A projectile's hit reaches its shooter, while the
   * shooter is in the battle and a character. Every hit that reaches such a row is told to the
   * observers.
   *
   * <p>A hit on a target already below 1 hit point - killed earlier in the same drain by another
   * hit - lands, but the subtraction leaves before its tail, so the attacker's hook is not called
   * and nothing is dropped. The rider then still holds the dead target when the cleanup removes it,
   * and the removal notice starts its target-lost countdown as for any attacker, unless the rider
   * kept the target only for the lethal damage of its own shot in flight.
   *
   * @param attacker what landed the hit: an arena entity, a projectile, or anything else
   * @param hit the entity it hit
   * @param kills whether the hit killed it
   */
  private void referenceDrop(BattleEntity attacker, WorldEntity hit, boolean kills) {
    // A hit that lands without killing leaves the target below 1 hit point only when it was there
    // already: the subtraction takes nothing from such a target and skips the attacker's hook.
    if (!kills && hitPointsOf(hit) < 1) {
      return;
    }
    ProjectileEntity via = attacker instanceof ProjectileEntity projectile ? projectile : null;
    BattleEntity shooter = via != null ? via.getOwner() : attacker;
    if (!(shooter instanceof CharacterEntity unit)) {
      return;
    }
    UnitData data = unit.getData();
    if (data.ignoreTargetsWithBuff() == null || !data.deprioritizeTargetsWithBuff()) {
      return;
    }
    boolean active = unit.isActive(CharacterEntity.TARGETING_SLOT);
    TargetView dropped = active ? unit.dropReferenceOnHit() : null;
    events.referenceDroppedOnHit(unit, hit, via, kills, active, dropped);
  }

  /**
   * The death handler's reward: ManaOnDeathForOpponent, stored by the loader as ten times the
   * column, paid in ten-thousandths to the king in the killing side's tower slot. Outside a match
   * no king holds elixir and nothing is paid.
   */
  private void deathReward(WorldEntity dying, UnitData data, int killingSide) {
    int amount = data.manaOnDeathForOpponent() * 10;
    if (amount < 1 || kingElixir == null || killingSide < 0 || killingSide > 1) {
      return;
    }
    kingElixir.add(killingSide, amount);
    events.deathElixirPaid(dying, killingSide, amount);
  }

  /**
   * The death handler's hook: the dying unit's killed action alone, with what killed it as the
   * cause; the death slot schedules the death action (see {@link #slotDeathAction}). None when the
   * row has no killed action, when the death has no killing side, or when the unit killed itself on
   * the killing side. With no attacker the game's cause is the killing side alone and no object:
   * the action is scheduled with no cause when no action of its chain reads more of a cause than
   * its object. Refused rather than guessed: any other killed action with no attacker, whose chain
   * reads what a side alone holds, one whose attacker is an object of another kind, and one
   * scheduled after the tick's last pending pass.
   *
   * @param dying the entity that died
   * @param attacker what killed it, or null for nothing
   * @param data its row
   * @param killingSide the side of the killing hit, or -1 for none
   */
  private void killedHook(
      WorldEntity dying, BattleEntity attacker, UnitData data, int killingSide) {
    if (data.onKilledAction() == null || killingSide == -1) {
      return;
    }
    // Built here for a death with no attacker, whose chain is asked whether it reads its cause.
    BattleAction killed =
        attacker == null ? actions.build(data.onKilledAction(), binding(dying)) : null;
    ActionHolder cause;
    int side;
    if (killed != null && !chainReads(killed, BattleAction::readsSideAloneCause)) {
      // No action of the chain reads anything of the cause but the object a side alone lacks.
      cause = null;
      side = killingSide;
    } else if (attacker instanceof WorldEntity entity) {
      cause = entity.actionHolder();
      side = entity.side();
    } else if (attacker instanceof ProjectileEntity projectile) {
      cause = projectile.actionHolder();
      side = projectile.getSide();
    } else if (attacker instanceof AreaEffectEntity areaEffect) {
      cause = areaEffect.actionHolder();
      side = areaEffect.side();
    } else if (attacker == null) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died with no attacker and its killed action reads its cause, which would be a"
              + " side alone, not modelled");
    } else {
      throw new UnsupportedOperationException(
          dying.name()
              + " was killed by "
              + attacker.getClass().getSimpleName()
              + ", whose cause its killed action would read, not modelled");
    }
    if (attacker == dying && side == killingSide) {
      return;
    }
    if (!holder.hasPendingPassAhead()) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died after the tick's last pending pass, where its death hooks would leave with"
              + " it; such a death is not established");
    }
    List<String> hooks = List.of(data.onKilledAction());
    boolean inPendingPass = holder.isInPendingPass();
    events.deathHooksScheduled(dying, attacker, side, hooks, inPendingPass);
    if (killed == null) {
      killed = actions.build(data.onKilledAction(), binding(dying));
    }
    dying.actionHolder().schedule(killed, ActionHolder.OWN_DELAY, false, cause);
  }

  /**
   * Whether an action or any action chained after it, each scheduled with the same cause, answers
   * the given question about its cause. A chain that comes back to an action already asked has been
   * asked in full.
   */
  private static boolean chainReads(BattleAction action, Predicate<BattleAction> reads) {
    Set<BattleAction> asked = Collections.newSetFromMap(new IdentityHashMap<>());
    for (BattleAction step = action; step != null && asked.add(step); step = step.nextAction()) {
      if (reads.test(step)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The death slot's last act: the row's death action, built for the dying object and scheduled on
   * its own holder with the row's own delay, the dying object its own cause, rather than from the
   * death handler with what killed it. So an area effect it spawns is for the dying object's side,
   * and a death the slot runs without the death handler (a removal, a rider let go, a decay) has it
   * too. It runs in the next pending pass of the tick; after the tick's last pending pass the
   * object leaves at the closing cleanup with the entry unrun.
   *
   * @param dying the object dying
   * @param data its row
   */
  private void slotDeathAction(WorldEntity dying, UnitData data) {
    List<String> hooks = List.of(data.onDeathAction());
    boolean inPendingPass = holder.isInPendingPass();
    events.deathHooksScheduled(dying, dying, dying.side(), hooks, inPendingPass);
    dying
        .actionHolder()
        .schedule(
            actions.build(data.onDeathAction(), binding(dying)),
            ActionHolder.OWN_DELAY,
            false,
            dying.actionHolder());
  }

  /**
   * The death of an object without hit points that its state visit removes - a bomb as its deploy
   * ends: its death slot, with no death handler after it, so no hooks. It leaves at a later
   * cleanup, once the visit has asked for its removal.
   *
   * @param dying the object
   */
  void deathAtRemoval(WorldEntity dying) {
    events.diedAtRemoval(dying);
    deathSlot(dying, dying.getData());
  }

  /**
   * The death of a character whose lifetime decay took its last hit point, in its hit-points visit:
   * its death slot, with no death handler after it. It leaves at the closing cleanup of the tick.
   * Then its death action, and not its killed action, is scheduled on it with itself as the cause,
   * and runs in the tick's next pending pass.
   *
   * @param dying the character
   * @param hitPointsBefore its hit points before the decay's last step
   */
  void decayDeath(WorldEntity dying, int hitPointsBefore) {
    UnitData data = dying.getData();
    events.decayDied(dying, hitPointsBefore);
    // The death slot schedules the death action, on itself with itself as the cause.
    deathSlot(dying, data);
  }

  /**
   * One firing of a character's spawner: its row's spawn character, as many as the firing makes,
   * each created for the spawner's side at its level re-based on the child's rarity, walking at
   * once when it has a speed - or deploying for its own deploy time when the spawner's row gives
   * its children their deploy - with no first-tick immunity, registered inside the post-hook pass
   * with its registration visit, and joining the live list at the tick's closing cleanup.
   *
   * <p>With a radius the children stand on its ring, as a death spawn's do. With none each stands
   * in front of the spawner, the spawner's collision radius and its own away toward the enemy, at
   * the first quarter turn of that offset the in-front test accepts, or one unit right of the
   * spawner where it accepts none.
   *
   * <p>A row that sets an angle shift turns the ring by it and by the angle the spawner faces, so
   * the Night Witch's Bats stand at its sides whichever way it walks. A unit's spawner fires as a
   * building's does; a stun holds its timer, and a wave due on the tick the unit dies still comes.
   *
   * <p>Refused rather than guessed: a ring drawn from the spawner's least radius, and a child
   * without hit points, a building, one that paths to its point or one with a starting action of
   * its own.
   *
   * @param spawner the character whose spawner fires
   * @param row the row the firing makes: the spawn character whose turn it is
   * @param count how many children the firing makes
   * @param radius the ring's radius, or 0 to place in front
   */
  void liveSpawn(CharacterEntity spawner, String row, int count, int radius) {
    UnitData data = spawner.getData();
    UnitData child = spawnedRow(row);
    if (radius != 0 && data.deathSpawnMinRadius() != 0) {
      throw new UnsupportedOperationException(
          spawner.name()
              + "'s spawner draws the ring its children stand on, which is not modelled");
    }
    if (child.hitpoints() <= 0
        || child.building()
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          spawner.name()
              + "'s spawner makes "
              + child.name()
              + ", without hit points, a building, pathing to its point or starting an action,"
              + " which is not modelled");
    }
    int fromX = spawner.getView().getX();
    int fromY = spawner.getView().getY();
    for (int i = 0; i < count; i++) {
      int[] at =
          SpawnPlacement.position(
              fromX,
              fromY,
              i,
              count,
              false,
              radius,
              ringTurn(spawner),
              data.collisionRadius() + child.collisionRadius(),
              spawner.side() & 1,
              tileMap.width() * TileMap.CELL_UNITS,
              (px, py) -> SpawnPassable.passable(tileMap, px, py, child.collisionRadius()));
      int x = inset(at[0], tileMap.width());
      int y = inset(at[1], tileMap.height());
      int made = spawnCounts.merge(spawner.name(), 1, Integer::sum) - 1;
      CharacterEntity spawned =
          CharacterEntity.spawned(
              this,
              child,
              spawner.name() + "_" + made,
              spawner.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(spawner.getPackedLevel(), child.rarity())));
      // A row whose children deploy sets each deploying for its own deploy time.
      if (data.spawnCharacterWithDeploy()) {
        spawned.startDeploying();
      }
      cloneSpawn(spawner, spawned);
      holder.addRegistered(spawned);
      events.characterSpawned(spawner, spawned, x, y);
      // The spawner's last act on each child: its running actions hear of it.
      spawner.actions().childSpawned(spawned.getId());
    }
  }

  /**
   * One firing of a buff's spawner, through the character spawner: one child of the buff's spawn
   * object, made for its carrier's side at the carrier's level re-based on the child's rarity, in
   * front of the carrier as a character's spawner places it - the carrier's collision radius and
   * its own away toward the enemy, at the first quarter turn the in-front test accepts - with no
   * deploy and no first-tick immunity, registered at once with its registration visit, and joining
   * the live list at the next cleanup's fold. A clone carrier's child is a clone.
   *
   * <p>A child of the carrier's own row whose row limits its group, made by a carrier in a group,
   * is linked into the carrier's chain right after it. A buff whose spawner needs its carrier alive
   * files the carrier's id with the child: the fold admits it only while the carrier is listed and
   * not removable.
   *
   * <p>A firing with the carrier's chain already at the group's limit makes nothing; the visit
   * still counts it as one of the instance's firings.
   *
   * <p>A child whose carrier has left or is leaving by the fold is released there instead of
   * admitted: it leaves the chain and never joins the battle.
   *
   * <p>Refused rather than guessed: a carrier that is a tower or a building; and a child without
   * hit points, a building, one that paths to its point or one with a starting action of its own.
   *
   * @param carrier the entity that carries the buff
   * @param instance the instance whose spawner fires
   */
  void buffSpawn(WorldEntity carrier, BuffInstance instance) {
    BuffData buff = instance.getBuff();
    if (!(carrier instanceof CharacterEntity spawner) || spawner.getData().building()) {
      throw new UnsupportedOperationException(
          carrier.name()
              + " carries "
              + buff.name()
              + ", whose spawner fires on a tower or a building, which is not modelled");
    }
    UnitData data = spawner.getData();
    UnitData child = records.unit(buff.spawnObject());
    if (child.hitpoints() <= 0
        || child.building()
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          buff.name()
              + "'s spawner makes "
              + child.name()
              + ", without hit points, a building, pathing to its point or starting an action,"
              + " which is not modelled");
    }
    // The group: a carrier of the child's own row that is in a group counts its chain, from its
    // first unit to its last, and the call ends once the chain holds the limit. Nothing is made
    // then, and no one hears of it; the visit spends the firing all the same. A unit that died
    // still counts until it is released, as it leaves the chain only then.
    boolean grouped =
        child.groupMaxSize() >= 1 && spawner.inChain() && data.name().equals(child.name());
    if (grouped && spawner.chainSize() >= child.groupMaxSize()) {
      return;
    }
    int[] at =
        SpawnPlacement.position(
            spawner.getView().getX(),
            spawner.getView().getY(),
            0,
            1,
            false,
            0,
            data.collisionRadius() + child.collisionRadius(),
            spawner.side() & 1,
            tileMap.width() * TileMap.CELL_UNITS,
            (px, py) -> SpawnPassable.passable(tileMap, px, py, child.collisionRadius()));
    int x = inset(at[0], tileMap.width());
    int y = inset(at[1], tileMap.height());
    int made = spawnCounts.merge(spawner.name(), 1, Integer::sum) - 1;
    CharacterEntity spawned =
        CharacterEntity.spawned(
            this,
            child,
            spawner.name() + "_" + made,
            spawner.side(),
            x,
            y,
            PackedLevel.level(PackedLevel.pack(spawner.getPackedLevel(), child.rarity())));
    if (grouped) {
      spawner.linkSpawnIntoChain(spawned);
    }
    cloneSpawn(spawner, spawned);
    // With SpawnerAliveRequired the spawner's id is filed beside the child, which the fold reads.
    holder.addRegistered(spawned, buff.spawnerAliveRequired() ? spawner.getId() : 0);
    events.characterSpawned(spawner, spawned, x, y);
  }

  /**
   * The riders of a character whose row attaches its spawner's children, made as it enters the
   * deploying state: in a card play, before the character itself is handed to the holder, so they
   * take the lower ids and every pass visits them before it; for a played unit that waits its turn,
   * in its state visit once the wait has run out, so they follow it. As many as the row's spawn
   * number, on the ring of its spawn radius - turned by its angle shift and facing when it sets one
   * - or on the parent's point with no radius, each at the parent's level re-based on its rarity,
   * set deploying for the parent's deploy time and facing as the parent faces, registered at once
   * with its registration visit, which sees no parent yet, and attached to the parent after it:
   * from its next movement visit it is placed around the parent.
   *
   * <p>Refused rather than guessed: a rider without hit points, a building, one that paths to its
   * point, one with a starting action, one that has riders of its own, and a ring drawn from the
   * least radius.
   *
   * @param parent the character entering the deploying state
   */
  void attachRiders(CharacterEntity parent) {
    UnitData data = parent.getData();
    UnitData child = spawnedRow(data.spawnCharacter());
    if (child.hitpoints() <= 0
        || child.building()
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null
        || child.spawnAttach()
        || data.deathSpawnMinRadius() != 0) {
      throw new UnsupportedOperationException(
          parent.name()
              + "'s riders "
              + child.name()
              + " lack hit points, are a building, path to their point, start an action, carry"
              + " riders or stand on a drawn ring, which is not modelled");
    }
    int count = data.spawnNumber();
    int radius = data.spawnRadius();
    int fromX = parent.getView().getX();
    int fromY = parent.getView().getY();
    for (int i = 0; i < count; i++) {
      // Its angle on the ring before any turn, which gives its share of the arc it rides on.
      int angle = (count - 1 - i) * 360 / count;
      int[] at =
          SpawnPlacement.position(
              fromX,
              fromY,
              i,
              count,
              true,
              radius,
              ringTurn(parent),
              data.collisionRadius() + child.collisionRadius(),
              parent.side() & 1,
              tileMap.width() * TileMap.CELL_UNITS,
              (px, py) -> SpawnPassable.passable(tileMap, px, py, child.collisionRadius()));
      int x = inset(at[0], tileMap.width());
      int y = inset(at[1], tileMap.height());
      int made = spawnCounts.merge(parent.name(), 1, Integer::sum) - 1;
      CharacterEntity rider =
          CharacterEntity.spawned(
              this,
              child,
              parent.name() + "_" + made,
              parent.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(parent.getPackedLevel(), child.rarity())));
      if (data.deployTimeMs() != 0) {
        rider.deployFor(data.deployTimeMs());
        rider.getView().setDirX(parent.getView().getDirX());
        rider.getView().setDirY(parent.getView().getDirY());
      }
      holder.addRegistered(rider);
      events.characterSpawned(parent, rider, x, y);
      rider.attachTo(parent, angle);
      events.riderAttached(parent, rider, i, angle);
    }
  }

  /**
   * A rider let go as its parent leaves the holder, in the parent's removal notice: its death slot
   * runs - its death spawn stands where it rode - without the death handler, and it leaves at the
   * same cleanup. A parent whose row sets the inherited ignore list is refused.
   *
   * @param rider the rider
   * @param parent the parent that left
   */
  void parentLeft(CharacterEntity rider, CharacterEntity parent) {
    if (parent.getData().deathInheritIgnoreList()) {
      // Every entity accepted then would list the rider's id, which no run holds.
      throw new UnsupportedOperationException(
          parent.name() + " lets its riders go under an inherited ignore list, which is not held");
    }
    events.parentLeft(rider, parent);
    deathSlot(rider, rider.getData());
  }

  /**
   * The degrees a spawner's ring is turned by: for a row that sets an angle shift, the shift plus
   * the angle the source faces, its heading as whole degrees; for any other row, none.
   */
  private static int ringTurn(WorldEntity source) {
    int shift = source.getData().spawnAngleShift();
    if (shift == 0) {
      return 0;
    }
    return shift + FixedMath.angleOfVector(source.getView().getDirX(), source.getView().getDirY());
  }

  /** The collision radius a thrown spell's height is taken from when the king has none. */
  private static final int THROWN_DEFAULT_RADIUS = 1400;

  /** The spell whose projectiles the cast lays out on a ring, by its name. */
  private static final String ARROWS = "Arrows";

  /** The globals value that would make Arrows' projectiles one whole chain; false in the data. */
  private static final boolean DEFLECT_ARROWS_AS_WHOLE = false;

  /**
   * The cast of a spell card at its placed point, in the command pass of its play: the area effect
   * at the point, or the projectile from the side's king tower - from its centre at three times its
   * collision radius, with no target, aimed at the point - both at the card's level and handed to
   * the holder, whose opening cleanup of the same step admits them. A troop card's projectile is
   * cast the same way, but from the point less five times that radius along the length, whichever
   * side plays, before the play makes its units.
   *
   * @param card the spell card, or a troop card that casts a projectile
   * @param cardLevel the card's level as the play gives it, packed
   * @param side the playing side
   * @param x the placed point along the width
   * @param y the placed point along the length
   * @param name the play's name, which a cast area effect names as its source
   * @param play the play, by its king's count of card plays before it, which each projectile cast
   *     carries; -1 outside a match
   * @param castId the play's cast id (see {@link SpawnHost#castId()}), which the cast stamps on its
   *     area effect and on each of its projectiles
   */
  public void castSpell(
      DeployCard card, int cardLevel, int side, int x, int y, String name, int play, int castId) {
    AreaEffectEntity areaEffect = null;
    if (card.areaEffect() != null) {
      areaEffect = createAreaEffect(card.areaEffect(), x, y, side, cardLevel, null, "cast", name);
      areaEffect.setCastId(castId);
    }
    ProjectileEntity firstProjectile = null;
    if (card.projectile() != null) {
      ProjectileData data = records.projectile(card.projectile());
      if (!data.unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            card.name()
                + " casts "
                + data.name()
                + ", which sets columns its impact does not model: "
                + data.unmodelledColumns());
      }
      TowerEntity king = kingTower(side);
      checkState(king != null, () -> "side " + side + " has no king tower to cast from");
      firstProjectile = castProjectiles(card, data, king, cardLevel, side, x, y, play, castId);
    }
    if (card.onExecuteAction() != null) {
      // The cast's last step: the action runs on the king at once, with the first object the cast
      // made as its cause - its first projectile, else its area effect. A cast that made nothing
      // schedules nothing.
      ActionHolder cause =
          firstProjectile != null
              ? firstProjectile.actionHolder()
              : areaEffect != null ? areaEffect.actionHolder() : null;
      TowerEntity king = kingTower(side);
      checkState(king != null, () -> "side " + side + " has no king tower to run an action on");
      if (cause != null) {
        king.actionHolder()
            .schedule(
                actions.build(card.onExecuteAction(), binding(king)),
                ActionHolder.OWN_DELAY,
                true,
                cause);
      }
    }
  }

  /**
   * A mirrored extra spell's projectile, thrown for a cast projectile: the row's projectile for the
   * cast's side, from where the cast stands to the cast's aim - its target's position when it homes
   * onto one - turned over across the arena's width, handed to the holder as the cast's are.
   *
   * @param source the cast projectile, the extra spell's cause
   * @param projectileName the projectile row the extra spell throws
   * @param action the extra spell's name
   */
  public void castMirroredExtraSpell(
      ProjectileEntity source, String projectileName, String action) {
    ProjectileData data = records.projectile(projectileName);
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          action
              + " throws "
              + data.name()
              + ", which sets columns its impact does not model: "
              + data.unmodelledColumns());
    }
    WorldEntity sourceTarget = source.getTarget();
    boolean homing = sourceTarget != null && source.getData().homing();
    int aimX = homing ? sourceTarget.getView().getX() : source.getAimX();
    int aimY = homing ? sourceTarget.getView().getY() : source.getAimY();
    ProjectileEntity projectile = new ProjectileEntity(this, data, source.side());
    projectile.castMirrored(source, tileMap.width() * TileMap.CELL_UNITS - aimX, aimY);
    holder.add(projectile);
    registrationPass(projectile);
  }

  /**
   * The projectiles a projectile shoots across its line, as the hero Elite Archer's ability shot
   * shoots its side shots from its starting action, before its first step. The line runs from where
   * the source stands to its aim - its target's position when it homes onto one - and its travel is
   * that line set to the row's ProjectileRange, or kept at its own length without one. The offsets
   * across it start at minus half the action's distance and grow by the distance over one less than
   * the count (at least 2); each is the line turned a quarter to the right ({@code (-dy, dx)}) set
   * to that offset, a negative offset to the other side. Each projectile starts at the source's
   * point moved by its offset and is shot at its start plus the travel, in order, each handed to
   * the holder as it is made, as a cast projectile's are.
   *
   * @param source the projectile that shoots them
   * @param action the action, which names the row, the count and the spread
   */
  public void shootProjectilesAcross(ProjectileEntity source, CreateParallelProjectiles action) {
    ProjectileData data = records.projectile(action.getProjectile());
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          action.name()
              + " shoots "
              + data.name()
              + ", which sets columns its flight does not model: "
              + data.unmodelledColumns());
    }
    WorldEntity sourceTarget = source.getTarget();
    boolean homing = sourceTarget != null && source.getData().homing();
    int dx = (homing ? sourceTarget.getView().getX() : source.getAimX()) - source.getX();
    int dy = (homing ? sourceTarget.getView().getY() : source.getAimY()) - source.getY();
    int[] travel = {dx, dy};
    FixedMath.normalize(
        travel,
        data.projectileRange() >= 1 ? data.projectileRange() : FixedMath.guardedDistance(dx, dy));
    int count = action.getCount();
    int spacing = action.getDistance() / (Math.max(count, 2) - 1);
    int offset = -(action.getDistance() / 2);
    for (int k = 0; k < count; k++) {
      int[] across = {-dy, dx};
      FixedMath.normalize(across, offset);
      int sx = source.getX() + across[0];
      int sy = source.getY() + across[1];
      ProjectileEntity projectile = new ProjectileEntity(this, data, source.side());
      projectile.launchAcross(source, sx, sy, sx + travel[0], sy + travel[1]);
      holder.add(projectile);
      registrationPass(projectile);
      offset += spacing;
    }
  }

  /**
   * A spell's projectiles, wave by wave: each wave a projectile at a time, the wave's delay plus
   * the projectile interval for each. Arrows' waves are chains whose damage lands on ring points:
   * the first on the placed point, the rest on a ring of the spell's radius less six tenths of the
   * projectile's, at even angles; each aims at its ring point plus six tenths of the projectile's
   * radius turned by a battle random below 359, the offset from the placed point kept within nine
   * tenths of the spell's radius, and starts at the king tower plus a quarter of that offset across
   * and the whole of it along. A chain shares its circle, the placed point and the spell's radius,
   * and the ids it has hit. Each carries the play that cast it.
   *
   * @return the first projectile cast
   */
  private ProjectileEntity castProjectiles(
      DeployCard card,
      ProjectileData data,
      TowerEntity king,
      int cardLevel,
      int side,
      int x,
      int y,
      int play,
      int castId) {
    int count = Math.max(card.multipleProjectiles(), 1);
    int waves = Math.max(card.projectileWaves(), 1);
    boolean ring = card.name().equals(ARROWS) && !DEFLECT_ARROWS_AS_WHOLE;
    if (count > 1 && !ring) {
      throw new UnsupportedOperationException(
          card.name() + " casts several projectiles of a random spread, which is not modelled");
    }
    int radius = card.radius();
    int stepRing = count > 1 ? 360 / (count - 1) : 0;
    int r90 = radius * 90 / 100;
    int r90sq = r90 * r90;
    int kx = king.getView().getX();
    int ky = king.getView().getY();
    int height = 3 * king.getData().collisionRadius();
    int base = 0;
    ProjectileEntity first = null;
    for (int wave = 0; wave < waves; wave++) {
      int delay = base;
      ProjectileChain chain = ring ? new ProjectileChain(x, y, radius) : null;
      for (int i = 0; i < count; i++) {
        int[] vec = {0, 0};
        int rx = x;
        int ry = y;
        if (i != 0 && ring) {
          vec = new int[] {radius - data.radius() * 60 / 100, 0};
          FixedMath.rotate1024(vec, stepRing * (i - 1));
          rx = vec[0] + x;
          ry = vec[1] + y;
        }
        int tx = x;
        int ty = y;
        if (ring) {
          // The jitter: six tenths of the projectile's radius, turned by a battle random.
          vec = new int[] {0, data.radius() * 60 / 100};
          FixedMath.rotate1024(vec, random.next(359));
          vec[0] += rx - x;
          vec[1] += ry - y;
          if (lengthSquared(vec[0], vec[1]) > r90sq) {
            FixedMath.normalize(vec, r90);
          }
          tx = vec[0] + x;
          ty = vec[1] + y;
        }
        ProjectileEntity projectile = new ProjectileEntity(this, data, side);
        if (card.spellAsDeploy()) {
          // Thrown: from MinDistance behind the point, toward the caster's own side, at five
          // times the king's collision radius, onto the point.
          if (data.spawnProjectile() == null) {
            throw new UnsupportedOperationException(
                card.name() + " is thrown without a projectile to spawn, which is not modelled");
          }
          int direction = ky >= tileMap.height() * 250 ? -1 : 1;
          int radiusOrDefault = king.getData().collisionRadius();
          radiusOrDefault = radiusOrDefault != 0 ? radiusOrDefault : THROWN_DEFAULT_RADIUS;
          projectile.cast(
              king,
              cardLevel,
              tx,
              ty - direction * data.minDistance(),
              5 * radiusOrDefault,
              tx,
              ty,
              delay,
              play);
        } else if (!card.spell()) {
          // A troop card's: from the point less five times the king's collision radius along the
          // length, whichever side plays, at three times it, onto the point.
          int collision = king.getData().collisionRadius();
          projectile.cast(king, cardLevel, tx, ty - 5 * collision, height, tx, ty, delay, play);
        } else {
          projectile.cast(
              king, cardLevel, kx + (vec[0] >> 2), vec[1] + ky, height, tx, ty, delay, play);
        }
        projectile.setCastId(castId);
        holder.add(projectile);
        registrationPass(projectile);
        if (chain != null) {
          projectile.joinChain(chain, rx, ry);
        }
        if (first == null) {
          first = projectile;
        }
        delay += card.projectileIntervalMs();
      }
      base += card.projectileWaveIntervalMs();
    }
    return first;
  }

  /** A vector's squared length; the largest int when a component or the sum would overflow. */
  private static int lengthSquared(int x, int y) {
    if (x < -46340 || x > 46340 || y < -46340 || y > 46340) {
      return Integer.MAX_VALUE;
    }
    long sum = (long) x * x + (long) y * y;
    return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
  }

  /**
   * The area effect a projectile's impact makes: at the impact point, for the projectile's side and
   * at its level re-based on the area effect's rarity, handed to the holder, which gives it its id
   * at once and admits it at the tick's closing cleanup, where the projectile itself leaves. The
   * projectile is its parent and the projectile's target its own; the deflection, which is not
   * modelled, is the only reader known of the one, and none is known of the other, so neither is
   * carried. A row that follows its target follows the projectile's target, a unit or a tower, when
   * it still has one; without one it follows nothing and stays on the impact point.
   *
   * <p>It is created as every game object is, its point kept the creation's inset inside each edge
   * of the arena: a projectile may land beyond the arena's side (a shrapnel of the evolved
   * Firecracker flying on past its shell's target), and its area effect then stands at the edge's
   * inset, not where the projectile ended.
   *
   * @param projectile the projectile that landed
   * @param x the impact point along the width
   * @param y the impact point along the length
   */
  public void impactAreaEffect(ProjectileEntity projectile, int x, int y) {
    AreaEffectEntity areaEffect =
        createAreaEffect(
            projectile.getData().spawnAreaEffectObject(),
            inset(x, tileMap.width()),
            inset(y, tileMap.height()),
            projectile.side(),
            projectile.getPackedLevel(),
            null,
            "projectile",
            projectile.name(),
            null,
            records.areaEffect(projectile.getData().spawnAreaEffectObject()).followsTarget()
                ? projectile.getTarget()
                : null);
    // The impact stamps it with the projectile's cast.
    areaEffect.setCastId(projectile.castId());
    events.projectileAreaEffect(projectile, areaEffect);
  }

  /**
   * The character spawn of a projectile's impact: its children in the card formation around the
   * impact point, the spread their collision radius when there are several, each created kept
   * inside the arena, at the projectile's level, deploying for the row's deploy time when it has
   * one, and registered with its registration visit; only then is a child on the ground moved off
   * the water, onto the point the relocation gives where the child then stands, so the step its
   * registration visit took is kept. Each child joins the live list at the next cleanup's fold,
   * which starts it: its row's starting action is scheduled then. A clone's projectile makes each
   * child a clone of 1 hit point of 1, the clone setter running after its deploy countdown and
   * before the add that registers it. A deflected projectile whose row names a deflected spawn
   * makes that in place of its spawned character, as many of them, for the side the deflection gave
   * it: the hero Barbarian Barrel's roll, turned, lets out the barbarian without the hero's ability
   * for the deflector's side, which no champion slot follows.
   *
   * @param projectile the projectile that landed
   * @param x the impact point along the width
   * @param y the impact point along the length
   */
  public void impactSpawn(ProjectileEntity projectile, int x, int y) {
    ProjectileData data = projectile.getData();
    // Deflected once or more, the impact takes the row's deflected spawn when it names one.
    UnitData child =
        spawnedRow(
            projectile.getDeflections() >= 1 && data.deflectedCharacterSpawn() != null
                ? data.deflectedCharacterSpawn()
                : data.spawnCharacter());
    if (child.hitpoints() <= 0 || child.building() || child.spawnPathfindSpeed() != 0) {
      throw new UnsupportedOperationException(
          data.name()
              + "'s impact makes "
              + child.name()
              + ", without hit points, a building or pathing to its point, which is not"
              + " modelled");
    }
    int count = data.spawnCharacterCount();
    int w = tileMap.width();
    int h = tileMap.height();
    int lane = LaneAssignment.lane(tileMap, x, y, -1, 0);
    for (int i = 0; i < count; i++) {
      int[] offset =
          Formation.offset(
              i,
              count,
              count == 1 ? 0 : child.collisionRadius(),
              0,
              (projectile.side() & 1) == 0 ? 1 : 0,
              lane,
              child.spawnAngleShift(),
              0,
              true,
              Standard1v1Battle.LANE_BASED_DEPLOY_SEQUENCE);
      int cx = inset(x + offset[0], w);
      int cy = inset(y + offset[1], h);
      // The creation takes the child's lane from where it is made with the impact point's x as the
      // reference and no flag: a child made across the centre column from the impact, whose nearest
      // road is the impact's own, is swapped to the impact's side.
      int childLane = LaneAssignment.lane(tileMap, cx, cy, x, 0);
      int made = spawnCounts.merge(projectile.name(), 1, Integer::sum) - 1;
      CharacterEntity spawned =
          CharacterEntity.spawned(
              this,
              child,
              projectile.name() + "_" + made,
              projectile.side(),
              cx,
              cy,
              PackedLevel.level(PackedLevel.pack(projectile.getPackedLevel(), child.rarity())),
              childLane);
      // A fixed priority per child: the k-th is taken as (20k)^2 nearer by a selection.
      if (data.spawnConstPriority()) {
        spawned.getView().setSquaredDistanceReduction((i * 20) * (i * 20));
      }
      if (data.spawnCharacterDeployTimeMs() >= 1) {
        spawned.deployFor(data.spawnCharacterDeployTimeMs());
      }
      // A clone's projectile makes a clone: the clone setter takes the projectile's mark, before
      // the add registers the child.
      if (projectile.isClone()) {
        spawned.markClone(null);
      }
      // The child is queued by the add that registers it now: it joins the live list at the next
      // cleanup's fold, which starts it, scheduling its row's starting action then. It carries the
      // projectile's play, so a champion slot that follows the play follows it.
      spawned.setDeployIndex(projectile.getDeployIndex());
      // The impact stamps it with the projectile's cast.
      spawned.setCastId(projectile.castId());
      spawned.startOnAdmission();
      holder.addRegistered(spawned);
      events.characterSpawned(projectile, spawned, cx, cy);
      // The relocation reads where the child stands after its registration visit, so the step
      // that visit took is kept, and runs only for a child on the ground (its height and height
      // offset).
      GridEntity view = spawned.getView();
      if (view.getZ() + view.getHeightOffset() == 0) {
        int packed =
            Relocation.relocate(
                grid.getWidth(), grid.getHeight(), view.getX(), view.getY(), -1, grid::water);
        view.setX(Relocation.unpackX(packed));
        view.setY(Relocation.unpackY(packed));
      }
    }
  }

  /**
   * The projectiles another one's impact launches: as many as the spawned row's spawn count, at
   * least one, in a fan. Each starts from the parent's position at the height the parent aimed at,
   * aimed beyond the parent's aim along the line it came, that line turned by the spawned row's
   * spawn radius, taken as degrees, times the projectile's step in the fan over the count - the
   * steps running from minus half the count up by one - at the parent's level, with the parent's
   * root. A parent whose row lays its spawn along the length aims each at the parent's minimum
   * distance straight beyond its aim, forward for its side - up the length for side 0, down for
   * side 1 - in place of the fan's line. Each is handed to the holder, which admits it at the next
   * cleanup, and one that flies to a point runs its first pass at once, its body widened by the
   * row's start radius, before the next is made. The parent is each one's source: an enchanting
   * copy it carries hands each a copy as it is registered, before that pass.
   *
   * @param parent the projectile that landed
   */
  public void impactProjectile(ProjectileEntity parent) {
    ProjectileData data = records.projectile(parent.getData().spawnProjectile());
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          parent.getData().name()
              + " spawns "
              + data.name()
              + ", which sets columns its flight does not model: "
              + data.unmodelledColumns());
    }
    int fan = Math.max(data.spawnCount(), 1);
    int step = -(fan >>> 1);
    for (int k = 0; k < fan; k++) {
      int[] vec = {parent.getAimX() - parent.getStartX(), parent.getAimY() - parent.getStartY()};
      FixedMath.rotate1024(vec, data.spawnRadius() * step / fan);
      ProjectileEntity projectile = new ProjectileEntity(this, data, parent.side());
      if (parent.getData().spawnAxisY()) {
        // The side's sign: -1 for side 0, whose forward is up the length, else 1.
        int sign = (parent.side() & 1) == 0 ? -1 : 1;
        projectile.launchSpawned(
            parent, parent.getAimX(), parent.getAimY() - sign * parent.getData().minDistance());
      } else {
        projectile.launchSpawned(parent, vec[0] + parent.getAimX(), vec[1] + parent.getAimY());
      }
      holder.add(projectile);
      registrationPass(projectile, parent);
      step++;
    }
  }

  /**
   * The pass a projectile flying to a point runs over what its body covers: the spatial index's box
   * of its body radius, widened by the extra, by its body's half height - or its circle without one
   * - buildings tested as squares. Unless the projectile never deflects, or is measured only at its
   * target's point and has not been deflected yet, the deflecting area effects the index lists come
   * first, in the index's order: the first that deflects it turns it around, and then nothing is
   * hit; one that answers no - it has arrived, or is on the area effect's team - lets the pass go
   * on. Otherwise one fresh hit id for the whole pass, and the travelling hit on each entity found,
   * in the index's order, until a hit finishes a projectile that stops at collisions; an area
   * effect the index lists takes no hit, having no hit points.
   *
   * <p>Refused rather than guessed: a pass without a body beside a deflecting area effect, which
   * would run the deflection pass instead, and every deflection {@link
   * ProjectileDeflection#deflect} does not model.
   *
   * @param projectile the projectile
   * @param x where the pass is centred, along the width
   * @param y where the pass is centred, along the length
   * @param extra how much the body radius is widened
   */
  public void cellPass(ProjectileEntity projectile, int x, int y, int extra) {
    ProjectileData data = projectile.getData();
    if (data.projectileRadius() < 1) {
      // Without a body the deflection pass runs at the pass's point and height, which finds nothing
      // without deflecting areas. Only a projectile that flies to a point, which has a body, is
      // handed here.
      if (!deflection.deflectors().isEmpty()) {
        throw new UnsupportedOperationException(
            projectile.name()
                + " passes cells without a body beside a deflecting area effect, not modelled");
      }
      return;
    }
    int radius = data.projectileRadius() + extra;
    int halfHeight = data.projectileRadiusY();
    List<GridEntity> found =
        index.query(new SpatialQuery(x, y, radius, halfHeight, false, true, 0, -1));
    boolean deflectable =
        (data.deflectBehaviour() & ProjectileData.NO_DEFLECT) == 0
            && ((data.deflectBehaviour() & ProjectileData.CHECK_ONLY_TARGET_POSITION) == 0
                || projectile.getDeflections() != 0);
    if (deflectable) {
      for (AreaEffectEntity deflector : deflection.listedDeflectors(x, y, radius, halfHeight)) {
        // The deflect handler answers no for a projectile that has arrived or is on the area
        // effect's own team, and does nothing else then. One it turns around hits nothing here.
        if (deflection.deflect(deflector, projectile)) {
          index.release(found);
          return;
        }
      }
    }
    int hitId = nextHitId();
    for (GridEntity view : found) {
      WorldEntity entity = known.get(view);
      if (entity != null && travellingHit(projectile, entity, x, y, hitId)) {
        break;
      }
    }
    index.release(found);
  }

  /**
   * The initial collision check of a projectile's first flight step, for a row with an initial
   * collision check filter and an owner of the character kind: the segment query from the owner's
   * position to the projectile's, with no width beyond the objects' own radii (a projectile's own
   * radius slot answers 0), the filter asked for the owner's team and row name. When the query
   * answers, one fresh hit id is drawn, even for an empty answer, and everything of the character
   * kind found, in the query's order, takes the projectile's travelling hit measured from the
   * owner's position, until one finishes the projectile. A projectile without the filter or without
   * an owner checks nothing.
   *
   * @param projectile the projectile on its first flight step
   */
  public void initialCollisionCheck(ProjectileEntity projectile) {
    GameObjectFilter filter = projectile.getData().initialCollisionCheckFilter();
    WorldEntity owner = projectile.getOwner();
    if (filter == null || owner == null || owner.kind() != BattleEntity.KIND_CHARACTER) {
      return;
    }
    int ox = owner.getView().getX();
    int oy = owner.getView().getY();
    List<WorldEntity> found =
        segmentQuery(
            owner.side() & 1,
            owner.getData().name(),
            ox,
            oy,
            projectile.getX(),
            projectile.getY(),
            0,
            filter);
    if (found == null) {
      return;
    }
    int hitId = nextHitId();
    for (WorldEntity entity : found) {
      if (entity.kind() == BattleEntity.KIND_CHARACTER
          && travellingHit(projectile, entity, ox, oy, hitId)) {
        break;
      }
    }
  }

  /**
   * Whether the damage queued for the drain will kill an entity, as a travelling hit just queued
   * asks it: the hits queued for the entity and not yet dealt, the one just queued among them, add
   * up to at least its hit points and shield. The hits counted are those of the queueing damage
   * entry, which adds each one's damage to the entity's queued total and the drain empties:
   * travelling hits, direct hits, a projectile's hit on its one target, the shares of a character's
   * or a projectile's area and the hits of a buff's damage over time. A damage-taking action's hit
   * is queued by an entry of its own, which adds nothing to the total: its amount is worked out
   * only at the drain, so it never counts here. The entity's hit points' own visit empties the
   * total in the third component pass (see {@link #emptyQueuedTotal}), so a hit queued before it in
   * the tick - a targeting visit's direct hit, an area's share - is not counted by a travelling hit
   * flying in the post-hooks, though it is dealt at the drain all the same; held by crawled battles
   * where a Hunter's volley met a target another unit's hit kills on the same step, each pellet
   * that reaches it then stopping at it.
   *
   * <p>Client 16.635.1 asks no global here and keeps only this test; its globals table has no
   * V16_PROJECTILE_DAMAGE_BUG. Client 16.402.17 asked that global first and, with it clear, held
   * the one hit's damage alone against the entity's hit points; every data version of its range set
   * it. So the global is not read, whatever a table holds.
   *
   * @param entity the entity hit
   */
  private boolean queuedKill(WorldEntity entity) {
    HitPoints hitPoints = entity.getHitPoints();
    int queued = 0;
    int start = queuedTotalStarts.getOrDefault(entity, 0);
    for (QueuedHit hit : queuedHits.subList(start, queuedHits.size())) {
      if (hit instanceof TravellingHitDue travelling && travelling.target() == entity) {
        queued += travelling.damage();
      } else if (hit instanceof DirectHitDue direct
          && known.get(direct.target().getEntity()) == entity) {
        queued += direct.damage();
      } else if (hit instanceof AreaHitDue area && area.victim() == entity) {
        queued += area.damage();
      } else if (hit instanceof TetherHitDue tether && tether.victim() == entity) {
        queued += tether.damage();
      } else if (hit instanceof CarriedPushHitDue push
          && known.get(push.target().getEntity()) == entity) {
        queued += push.damage();
      } else if (hit instanceof ProjectileAreaHitDue share && share.victim() == entity) {
        queued += share.damage();
      } else if (hit instanceof BuffHitDue buffHit && buffHit.target() == entity) {
        queued += buffHit.damage();
      } else if (hit instanceof KamikazeDrainDue drain && drain.unit() == entity) {
        queued += drain.damage();
      } else if (hit instanceof CaptureHitDue capture && capture.unit() == entity) {
        queued += capture.damage();
      } else if (hit instanceof ProjectileHitDue single && single.target() == entity) {
        queued += single.damage();
      }
      // A damage-taking action's hit adds nothing: its amount is worked out only at the drain.
    }
    return queued >= hitPoints.getHitPoints() + hitPoints.getShield();
  }

  /**
   * A flying body's hit on one entity it covers, as the translated hit runs it. Held by the
   * reference battles card_Log, spell_log_into_push, card_BarbLog and spell_barblog_into_push: the
   * damage at the level, the id list, and no push from a hit that kills; held by no run: the own
   * side spared, the crown-tower share, the untouchable listing, the air and jump checks, and the
   * push itself. Held by the Log's and the Bowler's over a jumping Mega Knight: a dash under a row
   * with a jump height spared, and the Log's hit once the Mega Knight has landed under it. In
   * order: none on its own side when it hits enemies only, none on an entity it has hit already; a
   * character that takes no part in contact in any state - under the no-check-collisions tag, as
   * the Little Prince's guard is while it deploys and charges, attached to a parent, or a building
   * of the characters table beside another building - is spared without being listed, as the
   * recorded Logs and bodies over a deploying guard show; a character in its dash under a row with
   * a dash immunity is spared without being listed, as the recorded Hunter pellets over a Golden
   * Knight in its chain show; any other untouchable character - one protected by the immunity left
   * after its dash among them - is listed as hit and spared, but for one tunnelling to its
   * placement, which is no exception here: it is listed, takes the hit into its queue, which the
   * damage entry then refuses at the drain, and stops a projectile that stops at collisions, as the
   * recorded Hunter pellets over a tunnelling Miner or a relocating evolved Goblin Drill show; a
   * character on a layer the projectile does not reach, in the air, or, for a projectile that does
   * not reach the air, in a jump or in a dash under a row with a jump height, is spared without
   * being listed. An entity with hit points takes the projectile's damage at its level, or its
   * crown-tower share, never a deflected share - a deflected body hits a crown tower for the whole
   * of it, as the Bowler's ball rolled back into its own princess tower does - as the listening
   * runs of a projectile with an action holder change it - the evolved Executioner's controller in
   * place of it - from the direction of the pass's centre, and is listed as hit, a carrier whose
   * row attaches its riders with the ids of its riders (held by the Bowler's over a Goblin Giant,
   * whose riders' Spear Goblins it then passes over); then a character whose movement is still on
   * is pushed the row's pushback away from the projectile, the row's push-all lifting the gates but
   * for the hidden one: a hidden character, a tunnelling one among them, is not pushed. A
   * projectile that stops at collisions is finished by a hit that landed on an entity with hit
   * points left, and the pass ends; held by the Hunter's pellets in the reference battle
   * card_Hunter.
   *
   * @return true when the hit finished the projectile
   */
  private boolean travellingHit(
      ProjectileEntity projectile, WorldEntity entity, int x, int y, int hitId) {
    ProjectileData data = projectile.getData();
    if (data.onlyEnemies() && (entity.side() & 1) == (projectile.side() & 1)) {
      return false;
    }
    int id = entity.getId();
    if (projectile.getHitIds().contains(id)) {
      return false;
    }
    // An entity its volley's group has doomed is passed over, and not listed as hit.
    if (projectile.getGroup() != null && projectile.getGroup().dooms(id)) {
      return false;
    }
    GridEntity view = entity.getView();
    // A character first answers whether it takes part in contact, the state clauses left out: one
    // under the no-check-collisions tag - the Little Prince's guard from its run's start to its
    // finish, its deploy included - or attached to a parent is spared without being listed.
    if (entity instanceof CharacterEntity && ContactRule.collidesInAnyState(view) == 0) {
      return false;
    }
    // A character in its dash under a row with a dash immunity is spared without being listed, so
    // the body can still hit it once its dash and the immunity left after it are over.
    if (entity instanceof CharacterEntity
        && view.getState() == GridEntityState.DASHING
        && entity.getData().dashImmuneToDamageTimeMs() > 0) {
      return false;
    }
    // A character tunnelling to its placement is not spared here: the hit is queued for it, and the
    // damage entry refuses it at the drain, but a projectile that stops at collisions stops at it.
    if (entity.untouchable() && !entity.tunnelling()) {
      projectile.getHitIds().add(id);
      return false;
    }
    if (!data.aoeToAir() && view.isAir()) {
      return false;
    }
    if (!data.aoeToGround() && !view.isAir()) {
      return false;
    }
    if (!data.aoeToAir() && view.getState() == GridEntityState.JUMPING) {
      return false;
    }
    // A dasher whose row has a jump height is off the ground while it dashes, as the Mega Knight
    // is in its jump: spared, and not listed, so the body can still hit it once it has landed.
    if (!data.aoeToAir()
        && view.getState() == GridEntityState.DASHING
        && entity.getData().jumpHeight() >= 1) {
      return false;
    }
    if (entity.getHitPoints() == null) {
      return false;
    }
    boolean crownTower = entity.getTargetView().crownTower();
    // The travelling hit reads no deflection: the row's amounts at the level.
    int damage = crownTower ? projectile.undeflectedTowerDamage() : projectile.undeflectedDamage();
    // A projectile with an action holder has its listening runs change the damage.
    if (projectile.hasActionHolder()) {
      damage = projectile.listenedDamage(damage, hitId, crownTower, entity);
    }
    boolean standing = entity.getHitPoints().getHitPoints() >= 1;
    projectile.getHitIds().add(id);
    // A carrier whose row attaches its riders has their ids listed with its own, so they are never
    // hit apart; a rider's death spawn that inherits the ignore list joins this list in its turn.
    if (entity instanceof CharacterEntity carrier && carrier.getData().spawnAttach()) {
      for (CharacterEntity rider : carrier.riders()) {
        projectile.getHitIds().add(rider.getId());
      }
    }
    // Queued for the drain, where the entity takes it and may die, after every movement visit of
    // the tick; then, should the entity's queued damage now kill it, it is doomed for the rest of
    // the volley.
    queuedHits.add(
        new TravellingHitDue(
            projectile, entity, damage, hitId, view.getX() - x, view.getY() - y, false));
    if (projectile.getGroup() != null && queuedKill(entity)) {
      projectile.getGroup().doom(id);
    }
    if (data.pushback() >= 1 && entity instanceof CharacterEntity character) {
      character.pushedByTravellingHit(
          projectile.getX(), projectile.getY(), data.pushback(), data.pushbackAll());
    }
    // A projectile that stops at collisions is finished by a hit on an entity that had hit points
    // left: any, as the hit waits for the drain, whose guards are not asked yet.
    if (standing && data.checkCollisions()) {
      projectile.finishOnCollision();
      return true;
    }
    return false;
  }

  /**
   * A shield broken by a hit, in the hit's own pass: every character of the tick with an attack
   * sequence mode that is attacking the entity has its attack reset, as an inferno's ramp is; then
   * the entity schedules its row's action for a broken shield, with what the hit came from as its
   * cause. The pushback the break would also read is refused with the row as it is created.
   *
   * @param broken the entity whose shield broke
   * @param cause what the breaking hit came from, or null for none
   */
  void shieldBroken(WorldEntity broken, SpawnHost cause) {
    for (WorldEntity entity : present) {
      if (entity instanceof CharacterEntity character
          && character.getData().attackSequence().mode() != 0
          && character.isActive(CharacterEntity.TARGETING_SLOT)
          && character.getTargeting().getReference() == broken.getTargetView()
          && character.getView().getState() == GridEntityState.ATTACKING) {
        character.getTargeting().clearAttack();
      }
    }
    broken.scheduleShieldLost(cause);
  }

  /**
   * Tells the observers an entity's broken shield scheduled its row's action.
   *
   * @param unit the entity
   * @param action the action's row
   * @param cause what the breaking hit came from, or null for none
   */
  void shieldLostScheduled(WorldEntity unit, String action, SpawnHost cause) {
    boolean inPendingPass = holder.isInPendingPass();
    events.shieldLostScheduled(unit, action, cause, inPendingPass);
  }

  /** Tells the observers a character's spawner fired. */

  /**
   * The death slot: what a dying object's row does as it dies, in order - its starting buff taken
   * off; its ManaOnDeath paid to its own side's king; its area effect at its point (kept the
   * creation's inset inside the arena), for its side and at its level, a clone's for a dying clone;
   * what its buffs leave; what the object switches off; its death damage; its death spawn; its
   * death projectiles. The switches come after the buffs: a child a buff leaves is visited as it is
   * made, and finds the dying object still moving. A death whose row sets a column of the slot the
   * battle does not model is refused.
   */
  private void deathSlot(WorldEntity dying, UnitData data) {
    if (!data.unmodelledDeathColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died, and what its row does as it dies is not modelled: "
              + data.unmodelledDeathColumns());
    }
    destroyedNotice(dying);
    // Its starting buff comes off every unit and tower that lists an instance of it with the dying
    // object as its parent; the level setter applies it with none.
    if (data.startingBuff() != null) {
      for (BattleEntity entity : holder.entities()) {
        if (entity instanceof WorldEntity carrier && carrier.getBuffs() != null) {
          carrier.getBuffs().removeParented(data.startingBuff(), dying);
        }
      }
    }
    deathMana(dying, data);
    if (data.deathAreaEffect() != null) {
      // It is created as every game object is, its point kept the creation's inset inside each
      // edge of the arena: a unit pushed onto the edge leaves its area effect the inset inside it.
      AreaEffectEntity deathArea =
          createAreaEffect(
              data.deathAreaEffect(),
              inset(dying.getView().getX(), tileMap.width()),
              inset(dying.getView().getY(), tileMap.height()),
              dying.side(),
              dying.getPackedLevel(),
              null,
              "death",
              dying.name());
      // The death slot gives its area effect the dying object's clone answer.
      if (isClone(dying)) {
        deathArea.markClone();
      }
    }
    buffDeathSpawns(dying);
    dying.deathSwitches();
    deathDamage(dying, data);
    deathSpawn(dying, data);
    deathProjectiles(dying, data);
    deathNotice(dying);
    if (data.onDeathAction() != null) {
      slotDeathAction(dying, data);
    }
  }

  /**
   * The death slot's payout: the row's ManaOnDeath, in whole elixir, to the king of the dying
   * object's own side, whatever killed it and whether or not it is a clone, up to the cap with what
   * goes above it counted as wasted. Outside a match no king holds elixir and nothing is paid.
   *
   * @param dying the object dying
   * @param data its row
   */
  private void deathMana(WorldEntity dying, UnitData data) {
    int side = dying.side();
    if (data.manaOnDeath() < 1 || kingElixir == null || side < 0 || side > 1) {
      return;
    }
    int amount = data.manaOnDeath() * KingElixir.SCALE;
    kingElixir.add(side, amount);
    events.deathManaPaid(dying, side, amount);
  }

  /**
   * The death slot's first act: every run listening for destroyed objects hears of the dying
   * object, in the order the runs started listening, before anything else the slot does.
   *
   * @param dying the object dying
   */
  private void destroyedNotice(WorldEntity dying) {
    if (destroyedListeners.isEmpty()) {
      return;
    }
    events.destroyedNoticed(dying, destroyedListeners.size());
    for (RunActionOnTroopDestroyed.Listener listener : new ArrayList<>(destroyedListeners)) {
      listener.destroyed(dying.filterSubject(), dying.getId(), dying.side(), dying.actionHolder());
    }
  }

  /**
   * A run starts listening for destroyed objects.
   *
   * @param owner the object the run is on
   * @param action the run's row name
   * @param listener the run
   */
  void listenForDestroyed(
      WorldEntity owner, String action, RunActionOnTroopDestroyed.Listener listener) {
    destroyedListeners.add(listener);
    events.destroyedListening(owner, action, true);
  }

  /**
   * A run stops listening for destroyed objects, as it is let go.
   *
   * @param owner the object the run is on
   * @param action the run's row name
   * @param listener the run
   */
  void unlistenForDestroyed(
      WorldEntity owner, String action, RunActionOnTroopDestroyed.Listener listener) {
    destroyedListeners.remove(listener);
    events.destroyedListening(owner, action, false);
  }

  /**
   * The death slot's notice, after its projectiles and with the dying object's hit points already
   * at 0: for a dying character that is not a clone, every character of the live list, in its order
   * and the dying one included, is told of the death and may count a soul for it. No position and
   * no clock is read: a death anywhere on the arena counts, as its death slot runs.
   *
   * @param dying the object dying
   */
  private void deathNotice(WorldEntity dying) {
    if (dying instanceof CharacterEntity unit && unit.isClone()) {
      return;
    }
    for (BattleEntity entity : new ArrayList<>(holder.entities())) {
      if (entity instanceof CharacterEntity receiver) {
        receiver.countSoul(dying);
      }
    }
  }

  /**
   * Whether one of the champion controllers of a unit's side follows it: in a match, the copy its
   * player's ability command reaches; outside one, no unit.
   *
   * @param unit the unit
   */
  boolean followedByController(CharacterEntity unit) {
    TowerEntity king = kingTower(unit.side());
    if (king == null) {
      return false;
    }
    for (int n = 1; n <= 2; n++) {
      ChampionController slot = king.championSlot(n);
      if (slot != null && slot.follows(unit)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The death slot's projectiles, after its death spawn: as many as the death spawn's count, each
   * on the dying object's side, launched from its point and height with the dying object as
   * launcher and owner, at its level re-based on the row's rarity, with no target and no delay.
   * Each is aimed at the row's spawn radius from that point, at an angle that starts at the row's
   * angle shift and steps by an equal share of the circle: the sine of the angle times the radius
   * over 1024 across the width, the sine of the angle and a quarter turn along the length, each
   * toward zero, the bottom side's turned across the width and the top side's along the length.
   * With no spawn radius - the Phoenix - the projectile is aimed at its own start. Each has its id
   * at once, joins the live list at the tick's closing cleanup and runs its registration pass.
   *
   * <p>A clone launches them as any unit: the game marks each a clone's projectile, which only what
   * the projectile spawns reads, so a row that only deals its damage (see {@link
   * ProjectileData#dealsOnlyDamage()}), as the Goblin Demolisher's, lands as the original's would,
   * and a row that also spawns characters where it lands, as the Phoenix's, makes each of them a
   * clone (see {@link #impactSpawn}): the Phoenix's egg is a clone of 1 hit point, and so is the
   * Phoenix it hatches.
   *
   * <p>Refused rather than guessed: a least radius, which draws each radius from the battle's
   * random source; a row with a spawner limit, whose count is what the limit has left; and a
   * clone's row that spawns an area effect or a projectile, runs an action, applies a buff or hops
   * on (see {@link ProjectileData#dealsDamageOrSpawnsCharacters()}), which no recording shows for a
   * clone.
   */
  private void deathProjectiles(WorldEntity dying, UnitData data) {
    ProjectileData row = data.deathSpawnProjectile();
    if (row == null) {
      return;
    }
    String refused = null;
    if (data.deathSpawnMinRadius() != 0) {
      refused = "draws each one's radius";
    } else if (data.spawnLimit() > 0) {
      refused = "counts them by what its spawner's limit has left";
    } else if (dying instanceof CharacterEntity character
        && character.isClone()
        && !row.dealsDamageOrSpawnsCharacters()) {
      refused = "is a clone, whose projectiles carry it to what they spawn";
    }
    boolean clone = dying instanceof CharacterEntity unit && unit.isClone();
    if (refused != null) {
      throw new UnsupportedOperationException(
          dying.name()
              + " launches "
              + row.name()
              + " as it dies and "
              + refused
              + ", not modelled");
    }
    int count = data.deathSpawnCount();
    int step = 360 / count;
    int radius = data.spawnRadius();
    int angle = data.spawnAngleShift();
    int x = dying.getView().getX();
    int y = dying.getView().getY();
    boolean bottom = (dying.side() & 1) == 0;
    for (int i = 0; i < count; i++) {
      int dx = FixedMath.div(FixedMath.sine1024(angle) * radius, 1024);
      int dy = FixedMath.div(FixedMath.sine1024(angle + 90) * radius, 1024);
      ProjectileEntity projectile = new ProjectileEntity(this, row, dying.side());
      ProjectileLauncher.launchOnDeath(
          projectile, dying, bottom ? x - dx : x + dx, bottom ? y + dy : y - dy);
      if (clone) {
        projectile.markClone();
      }
      launch(projectile);
      events.deathProjectileLaunched(dying, projectile);
      angle += step;
    }
  }

  /**
   * What the buffs a dying object carries leave as it dies, each listed instance in turn from the
   * first: a buff with a death spawn makes its characters through the character spawner, unless the
   * dying object is a building. The instances stay listed; they leave with the object.
   *
   * <p>The children stand in front of the dying object, the dying object's collision radius and the
   * child's away toward its enemy, or on its point for a row that spawns on the same location. Each
   * is made at the instance's level re-based on the child's rarity, for the dying object's opponent
   * when the row spawns for the enemy, else for its side; it deploys when the row delays its
   * deploy, and for the child's own deploy time, facing the way the dying object faced; it has no
   * first-tick immunity. It is registered with its registration visit inside the pass of the death
   * and joins the live list at the tick's closing cleanup, whose fold starts it as it starts any
   * object the spawner queued: its row's starting action - the Witch Mother's curse hog's jump
   * check - is scheduled then and runs in its first pending pass of the next tick.
   *
   * <p>Refused rather than guessed: a ring (a death spawn radius), and a child that is a building
   * or paths to its point. A dying row that spawns the same unit instead is refused with the death
   * columns.
   */
  private void buffDeathSpawns(WorldEntity dying) {
    if (dying.getBuffs() == null || dying.getData().building()) {
      return;
    }
    for (BuffInstance instance : new ArrayList<>(dying.getBuffs().items())) {
      BuffData buff = instance.getBuff();
      if (buff.deathSpawn() == null) {
        continue;
      }
      UnitData child = spawnedRow(buff.deathSpawn());
      if (buff.deathSpawnRadius() != 0 || child.building() || child.spawnPathfindSpeed() != 0) {
        throw new UnsupportedOperationException(
            dying.name()
                + " died carrying "
                + buff.name()
                + ", whose death spawn "
                + child.name()
                + " stands on a ring, is a building or paths to its point, which is not"
                + " modelled");
      }
      int side = buff.deathSpawnIsEnemy() ? dying.side() ^ 1 : dying.side();
      int fromX = dying.getView().getX();
      int fromY = dying.getView().getY();
      List<CharacterEntity> made = new ArrayList<>();
      for (int i = 0; i < buff.deathSpawnCount(); i++) {
        int[] at =
            SpawnPlacement.position(
                fromX,
                fromY,
                i,
                buff.deathSpawnCount(),
                buff.deathSpawnSameLocation(),
                0,
                dying.getData().collisionRadius() + child.collisionRadius(),
                dying.side() & 1,
                tileMap.width() * TileMap.CELL_UNITS,
                (px, py) -> SpawnPassable.passable(tileMap, px, py, child.collisionRadius()));
        int x = inset(at[0], tileMap.width());
        int y = inset(at[1], tileMap.height());
        int count = spawnCounts.merge(dying.name(), 1, Integer::sum) - 1;
        CharacterEntity spawned =
            CharacterEntity.spawned(
                this,
                child,
                dying.name() + "_" + count,
                side,
                x,
                y,
                PackedLevel.level(PackedLevel.pack(instance.getPackedLevel(), child.rarity())));
        if (buff.deathSpawnDeployDelay()) {
          spawned.startDeploying();
        }
        if (child.deployTimeMs() != 0) {
          // A child made with a deploy time faces the way the dying object faced.
          spawned.deployFor(child.deployTimeMs());
          spawned.getView().setDirX(dying.getView().getDirX());
          spawned.getView().setDirY(dying.getView().getDirY());
        }
        cloneSpawn(dying, spawned);
        // The spawner queues the child for the holder; the fold that takes it in starts it.
        spawned.startOnAdmission();
        holder.addRegistered(spawned);
        events.characterSpawned(dying, spawned, x, y);
        made.add(spawned);
      }
      events.buffDeathSpawn(dying, instance, made);
    }
  }

  /**
   * The death damage: the area damage around the dying entity, with the entity as its owner, as the
   * death slot deals it. Its damage is the row's at the entity's level, scaled as a card's damage
   * whatever the entity, and a crown tower takes it raised by the row's crown-tower percent. It has
   * no hit id, no limit short of a thousand victims and no split, hits air units when the row
   * attacks air and ground ones unless the row attacks air alone, and takes the entity's own
   * targeting as its validator's owner, but not its buildings-only column. Each victim still alive
   * after its damage, with a movement component and a row that does not ignore pushback, is pushed
   * the row's death pushback away from the entity. Nothing happens without a radius, or with
   * neither damage nor pushback.
   */
  private void deathDamage(WorldEntity dying, UnitData data) {
    int radius = data.deathDamageRadius();
    int push = data.deathPushBack();
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            data.deathDamage(),
            dying.getPackedLevel(),
            ScalingMode.CARD_DAMAGE,
            data.rarity());
    if (radius < 1 || (damage < 1 && push < 1)) {
      return;
    }
    int towerDamage = ((Math.max(data.crownTowerDamagePercent(), -100) + 100) * damage + 99) / 100;
    pushingArea(dying, radius, damage, towerDamage, 0, push, false);
  }

  /**
   * The landing hit of a dash with a radius: the area damage around the landing point, the dashing
   * unit as its owner, its damage the row's dash damage at the unit's level, taken whole by a crown
   * tower too, under the hit id the landing took, with no split and a thousand victims at most. It
   * hits air units when the row attacks air and ground ones unless the row attacks air alone, and
   * pushes each victim still alive, with a movement component and a row that does not ignore
   * pushback, the row's dash pushback away from the landing point. It collects its victims as a
   * projectile's area does: a unit the untouchable test refuses as it collects, the immunity left
   * after a dash counted - one dashing under a row with a dash immunity among them - is passed by,
   * neither damaged nor pushed.
   *
   * @param dasher the unit that landed
   * @param damage the dash damage at its level
   * @param hitId the id the landing took
   */
  void dashLandingArea(CharacterEntity dasher, int damage, int hitId) {
    UnitData data = dasher.getData();
    pushingArea(dasher, data.dashRadius(), damage, damage, hitId, data.dashPushBack(), true);
  }

  /**
   * An area damage around an entity, the entity as its owner and its targeting as the validator's,
   * that pushes each victim away from its centre: no split, a thousand victims at most, air units
   * when the entity's row attacks air and ground ones unless it attacks air alone. It passes by a
   * hidden victim, and with the collection gate also one the untouchable test refuses, the immunity
   * left after a dash counted.
   *
   * @param gated true for an area that collects through the untouchable test (a dash landing's),
   *     false for one that passes by a hidden victim only (the death damage's)
   */
  private void pushingArea(
      WorldEntity owner,
      int radius,
      int damage,
      int towerDamage,
      int hitId,
      int push,
      boolean gated) {
    UnitData data = owner.getData();
    int x = owner.getView().getX();
    int y = owner.getView().getY();
    boolean air = data.attacksAir();
    AreaDamage.Area area =
        new AreaDamage.Area(
            x,
            y,
            radius,
            damage,
            towerDamage,
            hitId,
            DEATH_DAMAGE_LIMIT,
            false,
            air,
            !air || data.attacksGround(),
            false,
            push,
            x,
            y);
    List<TargetView> entities = new ArrayList<>();
    for (WorldEntity entity : present) {
      entities.add(entity.getTargetView());
    }
    AreaDamage.Outcome outcome =
        AreaDamage.damage(
            owner.getTargeting(),
            entities,
            area,
            ValidatorQueries.standard1v1(),
            new AreaDamage.Queries() {
              @Override
              public boolean untouchable(TargetView victim) {
                WorldEntity entity = entityOf(victim.getEntity());
                return gated ? entity.passedByAreaCollection() : entity.passedBy(false);
              }

              @Override
              public DamageResult damage(TargetView victim, int dealt, int id) {
                return dealAreaDamage(owner, entityOf(victim.getEntity()), dealt, id);
              }

              @Override
              public boolean push(TargetView victim, int fromX, int fromY, int distance) {
                return entityOf(victim.getEntity()) instanceof CharacterEntity character
                    && character.pushedByArea(fromX, fromY, distance);
              }
            });
    events.areaDamaged(owner, area, outcome);
  }

  /**
   * The death spawn: the row's death spawn character, as many as its count, each created for the
   * dying object's side at its level re-based on the child's rarity, walking at once when it has a
   * speed and hit points, deploying for its own deploy time when it has none, or deploying for the
   * row's death spawn deploy time when the row has one; registered inside the pass of the kill with
   * its registration visit, untargetable at first, and joining the live list at the tick's closing
   * cleanup.
   *
   * <p>With a radius the children stand on its ring - child {@code i} of {@code n} at angle {@code
   * (n - 1 - i) * 360 / n}, turned by the row's angle shift and the angle the dying object faces
   * when the row sets a shift - the ring untested for passability; a least radius equal to the
   * radius draws nothing. A row that pushes its children puts each on the dying object and flies it
   * back to its ring point. A row that gives its children a fixed priority turns its ring over
   * across the width when the dying object stands in lane 1 and along the length for the top team,
   * asking the lane before each child, and the i-th child is taken as (80i)^2 nearer by a
   * selection. With no radius a single child stands on the dying object, and several stand together
   * in front of it, the dying object's collision radius and the child's away toward the enemy, the
   * first quarter turn of that offset the in-front test accepts; where it accepts none, the
   * children stand one unit right of the dying object.
   *
   * <p>A row with a second death spawn row makes its children after the first row's, on the same
   * ring: the ring is divided by both counts, the first row starts from the row's angle shift and
   * the second half a step past the opposite side, neither turned by the dying object's facing; a
   * second row with no radius stands in front of the dying object even as a single child.
   *
   * <p>Refused rather than guessed: a child that is a building with hit points, which replaces the
   * dying object, paths to its point or has a starting action of its own; a least radius below the
   * radius, which draws each child's ring radius from the battle's random source - no row sets one;
   * and a single child without hit points that has a range, which is pulled back half its range
   * along the dying object's facing - every such row has none, so no run holds it.
   */
  private void deathSpawn(WorldEntity dying, UnitData data) {
    if (data.deathSpawnCharacter() == null) {
      return;
    }
    int count = data.deathSpawnCount();
    if (data.deathSpawnCharacter2() == null) {
      deathSpawnRow(dying, data, data.deathSpawnCharacter(), count, count == 1, -1, 0);
      return;
    }
    // A second row shares the first row's ring: both divide it by the sum of the two counts. The
    // first starts from the row's angle shift, the second half a step past the opposite side,
    // and neither is turned by the way the dying object faces. The second row is made after the
    // first, always with its in-front offset.
    int total = count + data.deathSpawnCount2();
    if (total < 1) {
      throw new UnsupportedOperationException(
          dying.name() + "'s two death spawn rows count no child in all, which is not modelled");
    }
    int shift = data.spawnAngleShift();
    int second = shift + ((180 + 360 / total / 2) & 0xffff);
    deathSpawnRow(dying, data, data.deathSpawnCharacter(), count, count == 1, total, shift);
    deathSpawnRow(
        dying, data, data.deathSpawnCharacter2(), data.deathSpawnCount2(), false, total, second);
  }

  /**
   * One row of the death spawn: {@code count} children of the row, nothing for a count below 1.
   *
   * @param dying the dying object
   * @param data its row
   * @param row the row the children are made of
   * @param count how many
   * @param noOffset true for no in-front offset, as for a single child of the only or first row
   * @param total the children of the whole ring, which the angle step divides by, or -1 for this
   *     row's own count and the ring turned by the dying object's angle shift and facing
   * @param angleBase the angle the ring starts from with a total, in degrees
   */
  private void deathSpawnRow(
      WorldEntity dying,
      UnitData data,
      String row,
      int count,
      boolean noOffset,
      int total,
      int angleBase) {
    int radius = data.deathSpawnRadius();
    UnitData child = spawnedRow(row);
    // A building with hit points replaces the dying object instead; one without, a bomb, is made.
    if ((child.building() && child.hitpoints() > 0)
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          dying.name()
              + "'s death spawn "
              + child.name()
              + " replaces it, paths to its point or starts an action, which is not modelled");
    }
    // A least radius draws the ring for each child between it and the radius; every row that sets
    // one sets it to the radius, so the bound is 0, nothing is drawn and the ring is the radius.
    if (data.deathSpawnMinRadius() != 0 && data.deathSpawnMinRadius() != radius) {
      throw new UnsupportedOperationException(
          dying.name() + "'s death spawn draws its ring radius, which is not modelled");
    }
    if (data.deathSpawnDeployTimeMs() < 0) {
      throw new UnsupportedOperationException(
          dying.name() + "'s death spawn has a negative deploy time, which is not modelled");
    }
    int fromX = dying.getView().getX();
    int fromY = dying.getView().getY();
    for (int i = 0; i < count; i++) {
      int[] at;
      if (radius != 0) {
        at =
            total == -1
                ? SpawnPlacement.position(
                    fromX,
                    fromY,
                    i,
                    count,
                    false,
                    radius,
                    ringTurn(dying),
                    SpawnPlacement.NO_REACH,
                    0,
                    0,
                    (px, py) -> true)
                : SpawnPlacement.ring(fromX, fromY, i, count, total, angleBase, radius);
        // A ring whose children take a fixed priority asks the lane of the dying object's point
        // before each child, and is turned over by it and by the dying object's team.
        if (data.spawnConstPriority()) {
          int lane = LaneAssignment.lane(tileMap, fromX, fromY, -1, 0);
          events.ringLaneAsked(dying, fromX, fromY, lane);
          at = SpawnPlacement.mirrored(at, fromX, fromY, lane, dying.side() & 1);
        }
      } else {
        // A single child has no in-front offset; several share the one in-front point.
        at =
            SpawnPlacement.position(
                fromX,
                fromY,
                i,
                count,
                noOffset,
                0,
                dying.getData().collisionRadius() + child.collisionRadius(),
                dying.side() & 1,
                tileMap.width() * TileMap.CELL_UNITS,
                (px, py) -> SpawnPassable.passable(tileMap, px, py, child.collisionRadius()));
        if (count == 1 && child.hitpoints() <= 0 && child.range() != 0) {
          // Pulled back by half its range along the dying object's facing; every such row has a
          // range of 0, so no run holds the pullback.
          throw new UnsupportedOperationException(
              dying.name()
                  + "'s death spawn is an object without hit points with a range, which is not"
                  + " modelled");
        }
      }
      int x = inset(at[0], tileMap.width());
      int y = inset(at[1], tileMap.height());
      int made = spawnCounts.merge(dying.name(), 1, Integer::sum) - 1;
      CharacterEntity spawned =
          CharacterEntity.spawned(
              this,
              child,
              dying.name() + "_" + made,
              dying.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(dying.getPackedLevel(), child.rarity())));
      if (radius != 0 && data.deathSpawnPushback()) {
        spawned.flyBackFrom(fromX, fromY);
      }
      // A fixed priority per child: the i-th is taken as (80i)^2 nearer by a selection.
      if (data.spawnConstPriority()) {
        spawned.getView().setSquaredDistanceReduction((i * 80) * (i * 80));
      }
      if (child.hitpoints() <= 0 && child.deployTimeMs() >= 1) {
        spawned.startDeploying();
      }
      if (data.deathSpawnDeployTimeMs() > 0) {
        spawned.deployFor(data.deathSpawnDeployTimeMs());
        // A child made with a deploy time faces the way the dying object faced, as the spawner's
        // setter leaves it.
        spawned.getView().setDirX(dying.getView().getDirX());
        spawned.getView().setDirY(dying.getView().getDirY());
      }
      cloneSpawn(dying, spawned);
      // Where it is made: on its point, or on the dying object for one that flies back.
      int madeX = spawned.getView().getX();
      int madeY = spawned.getView().getY();
      holder.addRegistered(spawned);
      // A character whose row sets the inherited ignore list hands its place in every id list to
      // each child: whatever has listed it, as a body that hit it or its carrier, lists the child.
      if (data.deathInheritIgnoreList() && dying instanceof CharacterEntity) {
        inheritIdLists(dying, spawned);
      }
      if (DEATH_SPAWN_IMMUNE_FIRST_TICK) {
        spawned.startSpawnImmunity();
      }
      events.characterSpawned(dying, spawned, madeX, madeY);
    }
  }

  /**
   * A child taking a dying object's place in the id lists: every other object of the live list
   * whose id list holds the dying object's id lists the child's too. The objects that keep such a
   * list are a projectile, the ids its flying body has hit (with the riders of a carrier it hit)
   * and its chained hops' targets, and an area effect, the ids its projectiles were dropped onto;
   * the list of any other object is written only by such hand-overs - this one, a rider let go
   * under its parent's inherited list, a morph - so it never holds an id. Refused rather than
   * guessed: an area effect's list holding the dying object, which no run holds (the only rows that
   * hand their place over are riders, which no area effect chooses).
   *
   * @param dying the dying object
   * @param child the child made in its place
   */
  private void inheritIdLists(WorldEntity dying, WorldEntity child) {
    int held = dying.getId();
    int added = child.getId();
    for (BattleEntity entity : holder.entities()) {
      if (entity == child) {
        continue;
      }
      if (entity instanceof ProjectileEntity projectile) {
        if (projectile.getHitIds().contains(held)) {
          projectile.getHitIds().add(added);
        }
      } else if (entity instanceof AreaEffectEntity areaEffect
          && areaEffect.struckListHolds(held)) {
        throw new UnsupportedOperationException(
            child.name()
                + " would join the id list of "
                + areaEffect.name()
                + " in place of "
                + dying.name()
                + ", which is not modelled");
      }
    }
  }

  /**
   * Queues a typed hit, which the drain deals after the post-hooks of the tick; one queued after
   * that lands on the next tick.
   *
   * @param source the entity that deals it, or null for none
   * @param target the entity it lands on
   * @param type its damage type
   * @param amount its amount, before the type's pipeline
   */
  public void queueTypedHit(WorldEntity source, WorldEntity target, DamageType type, int amount) {
    int directionX = source == null ? 0 : target.getView().getX() - source.getView().getX();
    int directionY = source == null ? 0 : target.getView().getY() - source.getView().getY();
    queuedHits.add(new TypedHit(source, target, type, amount, directionX, directionY, null));
  }

  /**
   * Queues a damage-taking action's hit, which the drain deals after the post-hooks of the tick;
   * one queued after that lands on the next tick. Its source is the character, building or tower
   * that caused the action, the area effect whose hit ran it, or the projectile whose deflection
   * ran it on the deflector's parent; anything else that caused it is no source, and the level
   * scaling of a damage from such a cause is refused.
   *
   * @param cause what caused the action, or null for nothing
   * @param target the entity it lands on
   * @param damage the row's damage
   * @param added the added amount
   */
  void queueActionDamage(
      ActionOwner cause, WorldEntity target, TakeDamage.Damage damage, int added) {
    WorldEntity source = cause instanceof WorldEntity entity ? entity : null;
    AreaEffectEntity areaSource = cause instanceof AreaEffectEntity area ? area : null;
    ProjectileEntity projectileSource = cause instanceof ProjectileEntity shot ? shot : null;
    if (cause != null
        && source == null
        && areaSource == null
        && projectileSource == null
        && damage.levelScaled()) {
      throw new UnsupportedOperationException(
          "a damage-taking action's hit scaled by the level of "
              + cause.actionRowName()
              + ", neither a character nor an area effect, is not modelled");
    }
    int directionX = 0;
    int directionY = 0;
    if (source != null) {
      directionX = target.getView().getX() - source.getView().getX();
      directionY = target.getView().getY() - source.getView().getY();
    } else if (areaSource != null) {
      directionX = target.getView().getX() - areaSource.getX();
      directionY = target.getView().getY() - areaSource.getY();
    } else if (projectileSource != null) {
      // Taken from the projectile's point as from a character's: the direction is kept only for a
      // death, and no recording holds one by such a hit.
      directionX = target.getView().getX() - projectileSource.getX();
      directionY = target.getView().getY() - projectileSource.getY();
    }
    queuedHits.add(
        new ActionDamageDue(
            source, areaSource, projectileSource, target, damage, added, directionX, directionY));
  }

  /**
   * Queues a typed hit from an area effect, as a shaped area effect's damage does: the area effect
   * its source, with no direction. The drain deals it after the post-hooks of the tick.
   *
   * @param source the area effect that deals it
   * @param target the entity it lands on
   * @param type its damage type
   * @param amount its amount, before the type's pipeline
   */
  public void queueTypedHit(
      AreaEffectEntity source, WorldEntity target, DamageType type, int amount) {
    queuedHits.add(new TypedHit(null, target, type, amount, 0, 0, source));
  }

  /**
   * Queues the damage a filter form area effect deals to one object it lists, as a typed hit of its
   * damage type with the area effect its source, its direction from the area effect's point to
   * where the object stands now. The drain deals it after the post-hooks of the tick.
   *
   * @param source the area effect that deals it
   * @param target the object it lands on
   * @param damage the row's damage type
   */
  void queueAreaDamage(AreaEffectEntity source, WorldEntity target, AreaDamageType damage) {
    queuedHits.add(
        new TypedHit(
            null,
            target,
            null,
            0,
            target.getView().getX() - source.getX(),
            target.getView().getY() - source.getY(),
            source,
            damage));
  }

  /**
   * A filter form area effect's buff on one object it lists: applied with the area effect as the
   * source, at its level and for its side, for the given time, the area effect its parent when the
   * buff is controlled by its parent.
   *
   * @param areaEffect the area effect
   * @param target the object
   * @param buff the row's buff
   * @param time how long it lasts
   */
  void filterBuff(AreaEffectEntity areaEffect, WorldEntity target, BuffData buff, int time) {
    events.areaBuff(areaEffect, buff, time, List.of(target));
    AreaEffectEntity parent = buff.controlledByParent() ? areaEffect : null;
    target
        .getBuffs()
        .apply(buff, time, areaEffect.getPackedLevel(), areaEffect, areaEffect.side(), parent);
  }

  /**
   * Deals every queued hit, in the order they were queued, a hit queued while the drain runs - a
   * reflect's damage - included: the drain reads the queue's count again after every hit, and
   * empties the queue once it reaches the end. A typed hit: the type's pipeline, a damage id from
   * the battle's hit counter when the type takes one, the typed hit's entry, then the type's action
   * on the source and its action on the target, and the observers are told. A direct hit, a share
   * of a character's or a projectile's area, a tether's or a carried push's hit, a projectile's hit
   * on its one target, or a capture's hit: the damage dealt, with its reflect, its observers, its
   * death or the reference drop; a hit of a buff's damage over time; a circle's, a Kamikaze unit's,
   * a kill action's or a tiebreaker's clearing's kill; a reflect's damage.
   */
  private void drainTypedHits() {
    queuedTotalStarts.clear();
    // The drain reads the queue's length again after each hit, so a hit queued while it runs - a
    // reflected attack's damage - is dealt in this drain too, after every hit queued before it.
    for (int index = 0; index < queuedHits.size(); index++) {
      QueuedHit queued = queuedHits.get(index);
      if (queued instanceof ReflectedHitDue reflected) {
        reflectedHitNow(reflected);
        continue;
      }
      if (queued instanceof DirectHitDue direct) {
        DamageResult result =
            dealDamage(
                direct.attacker(),
                direct.target(),
                direct.damage(),
                direct.directionX(),
                direct.directionY(),
                false,
                direct.side());
        drainBuffOnDamage(direct.attacker(), direct.target(), result);
        continue;
      }
      if (queued instanceof AreaHitDue area) {
        dealAreaDamageNow(area.attacker(), area.victim(), area.damage(), area.hitId());
        continue;
      }
      if (queued instanceof TetherHitDue tether) {
        tetherHitNow(
            tether.owner(),
            tether.victim(),
            tether.damage(),
            tether.directionX(),
            tether.directionY());
        continue;
      }
      if (queued instanceof CarriedPushHitDue push) {
        dealDamage(
            push.attacker(),
            push.target(),
            push.damage(),
            push.directionX(),
            push.directionY(),
            true,
            -1,
            push.noCounter());
        continue;
      }
      if (queued instanceof ProjectileAreaHitDue share) {
        DamageResult result =
            dealProjectileDamage(
                share.projectile(), share.victim(), share.damage(), share.hitId(), 0, 0);
        drainProjectileBuff(share.projectile(), share.victim(), result);
        continue;
      }
      if (queued instanceof ProjectileHitDue hit) {
        DamageResult result =
            dealProjectileDamage(
                hit.projectile(),
                hit.target(),
                hit.damage(),
                hit.hitId(),
                hit.directionX(),
                hit.directionY());
        drainProjectileBuff(hit.projectile(), hit.target(), result);
        continue;
      }
      if (queued instanceof TravellingHitDue hit) {
        DamageResult result =
            dealProjectileDamage(
                hit.projectile(),
                hit.target(),
                hit.damage(),
                hit.hitId(),
                hit.directionX(),
                hit.directionY());
        if (hit.deflection()) {
          drainProjectileBuff(hit.projectile(), hit.target(), result);
        }
        continue;
      }
      if (queued instanceof ActionDamageDue actionDamage) {
        drainActionDamage(actionDamage);
        continue;
      }
      if (queued instanceof BuffHitDue buffHit) {
        dealBuffDamageNow(buffHit);
        continue;
      }
      if (queued instanceof CaptureHitDue capture) {
        dealDamage(capture.owner(), capture.unit().getTargetView(), capture.damage(), 0, 0, true);
        continue;
      }
      if (queued instanceof CircleKillDue kill) {
        circleKillNow(kill.target(), kill.radius(), kill.amount());
        continue;
      }
      if (queued instanceof KamikazeKillDue kamikaze) {
        kamikazeKillNow(kamikaze.unit(), kamikaze.amount());
        continue;
      }
      if (queued instanceof KamikazeDrainDue drain) {
        kamikazeDrainNow(drain.unit(), drain.damage());
        continue;
      }
      if (queued instanceof ActionKillDue kill) {
        killNow(kill.target(), kill.killer(), kill.amount());
        continue;
      }
      if (queued instanceof ClearingKillDue kill) {
        clearingKillNow(kill.target(), kill.amount());
        continue;
      }
      TypedHit hit = (TypedHit) queued;
      WorldEntity target = hit.target();
      if (target.getHitPoints() == null) {
        continue;
      }
      if (hit.areaDamage() != null) {
        drainAreaDamage(hit);
        continue;
      }
      if (hit.areaSource() != null) {
        drainAreaTypedHit(hit);
        continue;
      }
      int amount = pipeline(hit);
      int damageId = hit.type().acquireDamageId() ? nextHitId() : 0;
      // A source that has left the battle kills as nothing does.
      WorldEntity source = hit.source() == null || hit.source().isLeft() ? null : hit.source();
      DamageResult result =
          target.takeTypedHit(source, amount, damageId, hit.directionX(), hit.directionY());
      // The death runs inside the hit, before the type's actions are scheduled.
      if (result.died()) {
        target.die(source);
      }
      // The game applies a buff on damage at the drain, so it applies the source's after a typed
      // hit it lets through as well, one after the battle's end included; no reference holds a
      // typed hit from such a source.
      if (result.accepted() && source != null && source.getData().mayBuffOnDamage()) {
        throw new UnsupportedOperationException(
            source.name() + " deals a typed hit with a BuffOnDamage, not modelled");
      }
      if (hit.source() != null && hit.type().actionOnSource() != null) {
        hit.source()
            .actionHolder()
            .schedule(
                hit.type().actionOnSource(), ActionHolder.OWN_DELAY, false, target.actionHolder());
      }
      if (hit.type().actionOnTarget() != null) {
        target
            .actionHolder()
            .schedule(
                hit.type().actionOnTarget(),
                ActionHolder.OWN_DELAY,
                false,
                hit.source() == null ? null : hit.source().actionHolder());
      }
      events.typedHitDealt(source, target, amount, damageId, result);
    }
    queuedHits.clear();
    queuedTotalStarts.clear();
  }

  /**
   * Deals a damage-taking action's hit: nothing to a target without hit points; nothing at all from
   * a target that takes no damage; otherwise the damage's amount for the target - its tower amount
   * against a crown tower when it gives one - plus the added amount, scaled by the source's level
   * unless NoScaling, floored at 0 behind the source's percentages (which no buff changes) unless
   * NoAmplification - an area effect and a projectile have none - then lowered by the target's
   * protection and floored at 0 unless NoProtection. The damage entry deals it with the source
   * counting it (an area effect or a projectile counts nothing), the target's runs told whether it
   * is a Reflected hit, the hidden test lifted under DamagesHidden; then the reflect of a
   * reflecting target, the action's cause its attacker - a character, building or tower is struck
   * back as at any hit, an area effect never - and the death the hit causes, at once. A source that
   * has left the battle is no source, as the game clears every reference to an object as it leaves:
   * a projectile's hit is dealt as one without a source, unscaled, with no percentages, lowered by
   * the target's protection unless NoProtection, and with no attacker; so is an area effect's that
   * has left while every hit is held, as from the tiebreaker's clearing on, where it lands nothing;
   * a hit of no amount from a character or an area effect that has left deals 0, and the level
   * scaling of one with an amount is otherwise refused. A source whose row sets a buff on damage,
   * which the drain applies after a hit it lets through, is refused.
   *
   * <p>The level scaling is the card damage scaling of the source's own row and level: a
   * character's, a building's or a tower's, an area effect's, or a projectile's.
   */
  private void drainActionDamage(ActionDamageDue due) {
    WorldEntity target = due.target();
    if (target.getHitPoints() == null) {
      return;
    }
    WorldEntity source = due.source() == null || due.source().isLeft() ? null : due.source();
    AreaEffectEntity areaSource =
        due.areaSource() == null || liveObject(due.areaSource().getId()) != due.areaSource()
            ? null
            : due.areaSource();
    ProjectileEntity projectileSource =
        due.projectileSource() == null
                || liveObject(due.projectileSource().getId()) != due.projectileSource()
            ? null
            : due.projectileSource();
    TakeDamage.Damage damage = due.damage();
    int amount = 0;
    if ((target.getView().getFlags() & target.getView().getFlagBits().noDamage()) == 0) {
      amount = damage.amount(target.getTargetView().isCrownTowerTarget()) + due.added();
      // A source that has left is none of the three here: the game's reference to it is cleared as
      // it leaves, so its hit takes no level scaling and no percentages, as a hit without a source.
      // A recorded battle holds that for a projectile source. An area effect leaves before its hit
      // is drained only as the tiebreaker's clearing removes it, and from then on every hit is
      // held, so such a hit lands nothing whatever its amount: recorded battles hold that. For a
      // character source, and for an area effect source while hits are not held, no recording
      // holds the step it leaves at against the game's, so a scaled hit with an amount from one
      // that has left stays refused (an amount of 0 scales to 0 at any level).
      if (damage.levelScaled()
          && amount != 0
          && ((source == null && due.source() != null)
              || (areaSource == null && due.areaSource() != null && !hitsHeld))) {
        throw new UnsupportedOperationException(
            "a damage-taking action's hit of an amount scaled by the level of a character or area"
                + " effect source no longer in the battle is not modelled");
      }
      if (damage.levelScaled() && source != null) {
        amount =
            LevelScaling.scale(
                ScalingGlobals.standard(),
                amount,
                source.getPackedLevel(),
                ScalingMode.CARD_DAMAGE,
                source.getData().rarity());
      } else if (damage.levelScaled() && areaSource != null) {
        amount =
            LevelScaling.scale(
                ScalingGlobals.standard(),
                amount,
                areaSource.getPackedLevel(),
                ScalingMode.CARD_DAMAGE,
                areaSource.getData().rarity());
      } else if (damage.levelScaled() && projectileSource != null) {
        // A projectile's own row and level, as its own damage is scaled; it carries no buffs, so
        // no percentages follow.
        amount =
            LevelScaling.scale(
                ScalingGlobals.standard(),
                amount,
                projectileSource.packedLevel(),
                ScalingMode.CARD_DAMAGE,
                projectileSource.getData().rarity());
      }
      if (source != null && !damage.noAmplification()) {
        refuseTypedPercents(source, "a damage-taking action's hit");
        amount = Math.max(amount, 0);
      }
      if (!damage.noProtection()) {
        amount = Math.max(target.getBuffs().damageReduction(amount), 0);
      }
    }
    int before = hitPointsOf(target);
    // The hit's cause: the area effect, the projectile or the character, building or tower.
    SpawnHost cause =
        areaSource != null ? areaSource : projectileSource != null ? projectileSource : source;
    DamageResult result =
        target.takeActionDamage(
            source,
            cause,
            amount,
            damage.reflected(),
            damage.damagesHidden(),
            due.directionX(),
            due.directionY());
    // The action's cause is the hit's attacker: a reflecting target strikes back at a character,
    // building or tower that caused it, as at any hit, and at nothing for an area effect's.
    BattleEntity attacker =
        areaSource != null ? areaSource : projectileSource != null ? projectileSource : source;
    reflect(target, attacker, before, result, due.directionX(), due.directionY());
    if (result.died()) {
      target.die(attacker);
    }
    if (result.accepted() && source != null && source.getData().mayBuffOnDamage()) {
      throw new UnsupportedOperationException(
          source.name() + " deals a damage-taking action's hit with a BuffOnDamage, not modelled");
    }
    events.typedHitDealt(source, target, amount, 0, result);
  }

  /**
   * Deals a typed hit an area effect queued: the type's pipeline, in which the area effect, which
   * carries no buffs, leaves the source's multiplier out; a damage id when the type takes one; the
   * typed hit's entry, which no source counts and whose shield break names the area effect as its
   * cause; the reflect of a reflecting target, the area effect its attacker, which strikes nothing
   * back; the death the area effect caused; and the type's action on the target, the area effect
   * its cause. A type that scales by the source's level, whose level an area effect source would
   * give, and one with an action on the source are refused.
   */
  private void drainAreaTypedHit(TypedHit hit) {
    WorldEntity target = hit.target();
    AreaEffectEntity source = hit.areaSource();
    DamageType type = hit.type();
    if (type.enableLevelScaling() || type.actionOnSource() != null) {
      throw new UnsupportedOperationException(
          type.name()
              + " of a typed hit from "
              + source.getData().name()
              + " scales by its source's level or runs an action on its source, not modelled");
    }
    boolean noDamage =
        (target.getView().getFlags() & target.getView().getFlagBits().noDamage()) != 0;
    int amount =
        type.pipeline(hit.amount(), noDamage, false, null, 0, target.getBuffs()::damageReduction);
    int damageId = type.acquireDamageId() ? nextHitId() : 0;
    int before = hitPointsOf(target);
    DamageResult result = target.takeTypedHit(null, source, amount, damageId, 0, 0);
    // The area effect is the hit's attacker: a reflecting target's reflect strikes nothing back.
    reflect(target, source, before, result, 0, 0);
    if (result.died()) {
      target.die(source);
    }
    if (type.actionOnTarget() != null) {
      target
          .actionHolder()
          .schedule(type.actionOnTarget(), ActionHolder.OWN_DELAY, false, source.actionHolder());
    }
    events.typedHitDealt(null, target, amount, damageId, result);
  }

  /**
   * Deals the damage a filter form area effect queued: nothing to a target that takes no damage;
   * otherwise the type's amount for the target - its tower amount for a crown tower when it gives
   * one, its base amount else - scaled by the area effect's level against its rarity as card
   * damage, then lowered by the target's protection and floored at 0. An amount of 0 is dealt as
   * nothing at all; any other, with no damage id, through the typed hit's entry, which lowers it by
   * the target's protection once more and floors it at 1, which no source counts and whose shield
   * break names the area effect as its cause; the reflect of a reflecting target, the area effect
   * its attacker, which strikes nothing back; and the death the area effect caused.
   */
  private void drainAreaDamage(TypedHit hit) {
    WorldEntity target = hit.target();
    AreaEffectEntity source = hit.areaSource();
    if ((target.getView().getFlags() & target.getView().getFlagBits().noDamage()) != 0) {
      return;
    }
    int amount =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            hit.areaDamage().amount(target.getTargetView().crownTower()),
            source.getPackedLevel(),
            ScalingMode.CARD_DAMAGE,
            source.getData().rarity());
    amount = Math.max(target.getBuffs().damageReduction(amount), 0);
    if (amount == 0) {
      return;
    }
    int before = hitPointsOf(target);
    DamageResult result =
        target.takeTypedHit(null, source, amount, 0, hit.directionX(), hit.directionY());
    // The area effect is the hit's attacker: a reflecting target's reflect strikes nothing back.
    reflect(target, source, before, result, hit.directionX(), hit.directionY());
    if (result.died()) {
      target.die(source);
    }
    events.typedHitDealt(null, target, amount, 0, result);
  }

  /**
   * A typed hit's amount after its type's pipeline. The level scaling reads the source's own rarity
   * row and packed level while the source is in the battle; once it has left, the level it had and
   * the Common row, and the multiplier no longer counts it as a source.
   */
  private static int pipeline(TypedHit hit) {
    WorldEntity source = hit.source();
    boolean noDamage =
        (hit.target().getView().getFlags() & hit.target().getView().getFlagBits().noDamage()) != 0;
    if (source == null) {
      if (hit.type().enableLevelScaling()) {
        throw new UnsupportedOperationException(
            "the level of a typed hit without a source is not established; "
                + hit.type().name()
                + " asks for it");
      }
      return hit.type()
          .pipeline(
              hit.amount(), noDamage, false, null, 0, hit.target().getBuffs()::damageReduction);
    }
    boolean present = !source.isLeft();
    if (present && hit.type().enableDamageMultiplier()) {
      refuseTypedPercents(source, "a typed hit");
    }
    RarityTable rarity = present ? source.getData().rarity() : RarityTable.COMMON;
    return hit.type()
        .pipeline(
            hit.amount(),
            noDamage,
            present,
            rarity,
            source.getPackedLevel(),
            hit.target().getBuffs()::damageReduction);
  }

  /**
   * Refuses a typed hit, or a damage-taking action's hit, whose source carries a buff percent other
   * than 100: the game scales such a hit by the source's percents in its type's stage, floored at
   * 0, and once more at the damage entry, floored at 1, which the typed hit's entry here does not
   * model; no reference holds such a hit.
   */
  private static void refuseTypedPercents(WorldEntity source, String hit) {
    if (source.getBuffs().damagePercent() != 100
        || source.getBuffs().crownTowerDamagePercent() != 100) {
      throw new UnsupportedOperationException(
          hit + " from " + source.name() + ", whose buffs scale its damage, is not modelled");
    }
  }

  /**
   * A guard-spawning run's start on an area effect: the guard and its run.
   *
   * <p>In order: the point behind the area effect's, toward its own side, by the row's distance,
   * moved off water; the guard of the row's character created there, kept 250 inside the arena, for
   * the area effect's side, at its level re-based on the guard's rarity; the guard's run listed on
   * it with its tags, starting from the area effect's point; the guard's id and its registration
   * visit at once, the guard still walking as the level setter leaves it; the facing toward the
   * area effect's point; and its deploy, through its setter and the combat gate after it. It joins
   * the live list at the tick's closing cleanup and is first visited on the next tick.
   *
   * @param areaEffect the area effect the first run started on
   * @param action the row
   * @param phase the pending pass the first run started in
   */
  void spawnGuard(AreaEffectEntity areaEffect, SpawnGuard action, int phase) {
    SpawnGuard.Columns columns = action.getColumns();
    UnitData row = spawnedRow(columns.spawnData());
    areaEffect.refuseClonesSpawn("spawns " + row.name());
    if (row.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          action.name() + " makes " + row.name() + ", which starts an action, not modelled");
    }
    int x = areaEffect.getX();
    int y = areaEffect.getY();
    int sign = (areaEffect.side() & 1) == 0 ? 1 : -1;
    int behind = y - columns.appearBehindAtDistance() * sign;
    int packed = Relocation.relocate(grid.getWidth(), grid.getHeight(), x, behind, -1, grid::water);
    int gx = Relocation.unpackX(packed);
    int gy = Relocation.unpackY(packed);
    int made = spawnCounts.merge(areaEffect.name(), 1, Integer::sum) - 1;
    CharacterEntity guard =
        CharacterEntity.spawned(
            this,
            row,
            areaEffect.name() + "_" + made,
            areaEffect.side(),
            inset(gx, tileMap.width()),
            inset(gy, tileMap.height()),
            PackedLevel.level(PackedLevel.pack(areaEffect.getPackedLevel(), row.rarity())));
    guard.actionHolder().list(action.guardRun(guard.guardHost(), x, y));
    holder.addRegistered(guard);
    events.guardRegistered(guard);
    guard.faceToward(x, y);
    guard.deployAfterRegistration();
    events.guardStarted(areaEffect, action.name(), phase, guard, x, behind, gx, gy);
  }

  /** The blocked cell value a warp's landing avoids: not placeable, and no lane. */
  private static final int WARP_BLOCKED_CELL = 0x10;

  /** The rows a warp's landing searches each way for an allowed cell. */
  private static final int WARP_SEARCH_ROWS = 4;

  /**
   * A warp's perform: the unit's position plus the offset, negated for side 1, clamped into the
   * arena; a landing cell the row avoids replaced by the nearest allowed cell of its column within
   * four rows, the nearer half-row neighbour first at each distance, at that cell's centre, or by
   * the landing row's centre when none is allowed; the position written once; then the pending
   * damage reset, the route emptied and the reference dropped, as the row asks. The reset drops the
   * unit as the target of every projectile aimed at it, as entering a pathfinding state does; one
   * whose row keeps its target through it keeps the unit (see {@link
   * #resetPendingDamageAtWarp(CharacterEntity, String)}).
   *
   * @param unit the unit
   * @param action the row
   * @param phase the pending pass it runs in
   */
  void warp(CharacterEntity unit, WarpCharacter action, int phase) {
    WarpCharacter.Columns columns = action.getColumns();
    GridEntity view = unit.getView();
    int startX = view.getX();
    int startY = view.getY();
    int sign = unit.side() == 0 ? 1 : -1;
    int x = columns.warpX() * sign + startX;
    int y = columns.warpY() * sign + startY;
    int maxX = grid.getWidth() * TileMap.CELL_UNITS - 1;
    int maxY = grid.getHeight() * TileMap.CELL_UNITS - 1;
    x = x > 0 ? Math.min(x, maxX) : 0;
    y = y > 0 ? Math.min(y, maxY) : 0;
    if (columns.avoidWater() || columns.avoidBlocked()) {
      int col = x / TileMap.CELL_UNITS;
      int row = y / TileMap.CELL_UNITS;
      if (!warpLandingAllowed(columns, col, row)) {
        // The nearer half-row neighbour first, at each distance.
        int first = y - row * TileMap.CELL_UNITS >= TileMap.CELL_UNITS / 2 ? 1 : -1;
        int found = row;
        for (int k = 1; k <= WARP_SEARCH_ROWS; k++) {
          if (warpLandingAllowed(columns, col, row + first * k)) {
            found = row + first * k;
            break;
          }
          if (warpLandingAllowed(columns, col, row - first * k)) {
            found = row - first * k;
            break;
          }
        }
        y = found * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
      }
    }
    TargetView referenceBefore = unit.getUnit().targeting().getReference();
    unit.warpTo(x, y);
    if (columns.resetPendingDamage()) {
      resetPendingDamageAtWarp(unit, action.name());
    }
    if (columns.resetPath()) {
      unit.resetRoute();
    }
    if (columns.resetTarget()) {
      unit.resetTargetAfterWarp();
    }
    events.warped(
        unit,
        action.name(),
        phase,
        startX,
        startY,
        referenceBefore == null ? null : referenceBefore.name());
  }

  /**
   * A warp's pending damage reset, the instant warp's and the flying warp's start alike: the unit's
   * reset hook, the same as entering a pathfinding state. The damage on its way to the unit is set
   * to 0, then every projectile aimed at it whose row allows it loses it as its target, handing
   * nothing back; each flies on to its aim, where the unit last stood for it, and lands on nothing.
   *
   * <p>A projectile aimed at the unit whose row keeps its target through the reset
   * (AllowResetTarget cleared) is left alone: it keeps the unit as its target, goes on re-aiming at
   * it wherever the warp takes it, and lands on it, its arrival handing back its own part of the
   * emptied amount. The rows that keep their target deal no damage, so that part is 0 and the
   * hand-back changes nothing. One with damage on its way, whose hand-back from the emptied amount
   * no reference holds, is refused.
   *
   * @param unit the unit
   * @param action the warp row's name
   */
  void resetPendingDamageAtWarp(CharacterEntity unit, String action) {
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof ProjectileEntity p
          && p.getTarget() == unit
          && !p.getData().allowResetTarget()
          && p.registeredPendingAmount() != 0) {
        throw new UnsupportedOperationException(
            action
                + " warps "
                + unit.name()
                + " with "
                + p.name()
                + " aimed at it, whose row keeps its target through the reset with damage on its"
                + " way, not modelled");
      }
    }
    unit.getView().setPendingDamageAmount(0);
    dropProjectilesAimedAt(unit);
  }

  /**
   * Whether a warp may land on a cell: inside the arena, not water when the row avoids water, and
   * not the blocked value when it avoids blocked cells.
   */
  private boolean warpLandingAllowed(WarpCharacter.Columns columns, int col, int row) {
    if (row < 0 || col >= grid.getWidth() || row >= grid.getHeight()) {
      return false;
    }
    if (columns.avoidWater() && (grid.tiles(col, row) & TileMap.WATER_BIT) != 0) {
      return false;
    }
    return !(columns.avoidBlocked() && grid.tiles(col, row) == WARP_BLOCKED_CELL);
  }

  /** How far beyond its own radius the nearest other building is looked for, in game units. */
  private static final int BESIDE_BUILDING_SEARCH = 2000;

  /** The edge-to-edge gap below which a building of the characters table leaves contact. */
  private static final int BESIDE_BUILDING_GAP = 500;

  /**
   * Whether another building stands beside a building of the characters table, which takes it out
   * of contact: the nearest building other than itself - a listed character or crown tower that is
   * a building, a dying one still listed among them - is looked for over the live list in its
   * order, taken when its squared distance from the building's point is below the best so far and
   * below its own radius plus 2000, squared; the building is beside it when their distance, less
   * both radii, is below 500. A building found nowhere within reach leaves it in contact.
   *
   * @param self the building of the characters table that asks
   * @return true when the nearest other building is within 500 of it edge to edge
   */
  boolean besideAnotherBuilding(WorldEntity self) {
    GridEntity own = self.getView();
    int x = own.getX();
    int y = own.getY();
    GridEntity nearest = null;
    int best = Integer.MAX_VALUE;
    for (BattleEntity entity : holder.entities()) {
      if (!(entity instanceof WorldEntity other) || other == self) {
        continue;
      }
      GridEntity view = other.getView();
      if (!view.isBuilding()) {
        continue;
      }
      // Squared in 32 bits as the search takes it; the reach is compared without a sign.
      int dx = x - view.getX();
      int dy = y - view.getY();
      int squared = dx * dx + dy * dy;
      if (squared >= best) {
        continue;
      }
      int reach = view.getCollisionRadius() + BESIDE_BUILDING_SEARCH;
      if (Integer.compareUnsigned(squared, reach * reach) < 0) {
        best = squared;
        nearest = view;
      }
    }
    if (nearest == null) {
      return false;
    }
    int distance = FixedMath.guardedDistance(x - nearest.getX(), y - nearest.getY());
    return distance - own.getCollisionRadius() - nearest.getCollisionRadius() < BESIDE_BUILDING_GAP;
  }

  /**
   * The spawner: creates the children a spawn row's block describes, each one after the other.
   *
   * <p>For each child, in order: where it stands (on the point, one unit right of it over water, or
   * on the ring), kept 250 inside the arena; its creation, for the source's side, in the lane of
   * its own position; its level, the row's or the source's, re-based on the child's own rarity;
   * walking at once as the level setter leaves it, or deploying when the row asks or when the child
   * has no hit points and a deploy time, for the row's own deploy time when it has one; its id and
   * its registration visit at once, inside the pass that runs the spawn, over this tick's index, so
   * a push from a unit already standing there moves it; its first-tick immunity; and the action it
   * runs as it is spawned, which starts at once when it has no delay, since a pending pass is in
   * progress. It joins the live list at the tick's closing cleanup and is first visited on the next
   * tick.
   *
   * <p>A creation that ignores effects is the same creation: the flag only skips the spawn effect,
   * which is presentation.
   *
   * <p>A ring whose children take a fixed priority asks the lane of the point before each child and
   * is turned over across the width in lane 1 and along the length for the top team, and the i-th
   * child is taken as (80i)^2 nearer by a selection; a ring that pushes its children puts each on
   * the point and flies it back to its ring point. A child of a source other than a character made
   * with a deploy time faces along the length toward the enemy, a vector of length 1.
   *
   * <p>A ring around a character source reads the source's own row: an angle shift turns the ring
   * by the shift and the angle the source faces, as a character's own spawner turns it, and a row
   * whose death spawn pushes its children makes the ring push them although the spawn does not ask.
   * The source's running actions hear of each child, by its id, as the last step of its making, as
   * they hear of what a character's own spawner makes: the evolved Witch's soul drain counts the
   * skeletons of her interval spawn as hers.
   *
   * <p>Refused rather than guessed: a morph, a spawn for the other side, a ring around a character
   * whose row draws each child's radius or attaches its children, and a unit that paths to its
   * spawn point. A child without a speed stands where it is made, and one without hit points is
   * taken like any other. A child with a starting action of its own starts it as the cleanup's fold
   * admits it: the action is scheduled then, outside every pending pass.
   *
   * @param source the object the children are spawned from
   * @param arguments the block the row's perform works out
   * @return the children, in the order they were made
   */
  public List<SpawnHost> spawnCharacters(SpawnHost source, SpawnArguments arguments) {
    UnitData data = arguments.configuration();
    refuseUnestablished(source, arguments, data);
    // A character source's own row: its angle shift turns a ring, with the angle it faces, and its
    // death spawn's pushback makes a ring push its children when the spawn does not ask.
    WorldEntity ownRow =
        source instanceof WorldEntity entity && entity.isCharacter() ? entity : null;
    int turn = arguments.radius() != 0 && ownRow != null ? ringTurn(ownRow) : 0;
    boolean pushback =
        arguments.spawnPushback() || (ownRow != null && ownRow.getData().deathSpawnPushback());
    List<SpawnHost> made = new ArrayList<>();
    for (int i = 0; i < arguments.count(); i++) {
      int[] at =
          SpawnPlacement.position(
              arguments.x(),
              arguments.y(),
              i,
              arguments.count(),
              arguments.noOffset(),
              arguments.radius(),
              turn,
              SpawnPlacement.NO_REACH,
              0,
              0,
              (x, y) -> SpawnPassable.passable(tileMap, x, y, data.collisionRadius()));
      if (arguments.radius() != 0 && arguments.constPriority()) {
        // A ring whose children take a fixed priority asks the lane of the point before each
        // child, and is turned over by it and by the source's team.
        int lane = LaneAssignment.lane(tileMap, arguments.x(), arguments.y(), -1, 0);
        events.ringLaneAsked(source, arguments.x(), arguments.y(), lane);
        at = SpawnPlacement.mirrored(at, arguments.x(), arguments.y(), lane, source.side() & 1);
      }
      int x = inset(at[0], tileMap.width());
      int y = inset(at[1], tileMap.height());
      int level =
          arguments.level() == SpawnRow.SOURCE_LEVEL ? source.packedLevel() : arguments.level();
      int count = spawnCounts.merge(source.name(), 1, Integer::sum) - 1;
      CharacterEntity child =
          CharacterEntity.spawned(
              this,
              data,
              source.name() + "_" + count,
              source.side(),
              x,
              y,
              PackedLevel.level(PackedLevel.pack(level, data.rarity())));
      if (arguments.radius() != 0 && pushback) {
        // On the point, flying back to its ring point.
        child.flyBackFrom(arguments.x(), arguments.y());
      }
      // A fixed priority per child: the i-th is taken as (80i)^2 nearer by a selection.
      if (arguments.constPriority()) {
        child.getView().setSquaredDistanceReduction((i * 80) * (i * 80));
      }
      if (arguments.useDeploy() || deploysWithoutAsking(data, arguments.deployTimeMs())) {
        child.startDeploying();
      }
      if (arguments.deployTimeMs() != 0) {
        child.deployFor(arguments.deployTimeMs());
        // A spawn with a deploy time of its own hands the child a character source's facing, as
        // the evolved Goblin Drill's hide goblins face where the drill faced. Any other source
        // hands it a vector of its team's direction along the length, of length 1, not 256.
        if (source instanceof CharacterEntity character) {
          child.getView().setDirX(character.getView().getDirX());
          child.getView().setDirY(character.getView().getDirY());
        } else {
          child.getView().setDirX(0);
          child.getView().setDirY(AreaEffectEntity.yDirection(source.side()));
        }
      }
      // The spawner's clone setter, last before the hand-over: the child of a clone is a clone.
      cloneSpawn(source, child);
      // The child is registered now and joins the live list at the next cleanup's fold, which
      // starts it: its row's starting action is scheduled then.
      child.startOnAdmission();
      holder.addRegistered(child);
      if (arguments.deathSpawn() && DEATH_SPAWN_IMMUNE_FIRST_TICK) {
        child.startSpawnImmunity();
      }
      events.characterSpawned(source, child, x, y);
      if (arguments.action() != null) {
        child
            .actionHolder()
            .schedule(arguments.action(), ActionHolder.OWN_DELAY, false, source.actionHolder());
      }
      made.add(child);
      // The spawner's last act on each child: the source's running actions hear of it.
      ActionHolder actions = source.actionHolder();
      if (actions != null) {
        actions.childSpawned(child.getId());
      }
    }
    return made;
  }

  /**
   * Whether the spawner starts a child deploying although its spawn does not ask for a deploy: a
   * row without hit points, a bomb or a bottle, whose deploy time (the spawn's own when positive,
   * else the row's) is at least 1 and which does not walk to its spawn point. The spawner's deploy
   * decision (the row's Hitpoints at level 0, the deploy time, SpawnPathfindSpeed) is or-ed with
   * the spawn's own deploy flag before the state is set to deploying.
   */
  private static boolean deploysWithoutAsking(UnitData data, int spawnDeployTimeMs) {
    int deployTimeMs = spawnDeployTimeMs > 0 ? spawnDeployTimeMs : data.deployTimeMs();
    return data.hitpoints() == 0 && deployTimeMs >= 1 && data.spawnPathfindSpeed() == 0;
  }

  /**
   * The one-child positional spawner, as a Goblin Hut's life state calls it: one child of the row
   * on the point, or one unit right of it where the in-front test refuses the point, kept 250
   * inside the arena; created for the source's side at the source's level re-based on the child's
   * rarity, deploying for its row's deploy time, untargetable at first, and queued with no
   * registration visit, so it joins the live list at the tick's closing cleanup and is first
   * visited on the next tick.
   *
   * <p>Refused rather than guessed: a building, a unit that paths to its spawn point or limits its
   * group, and a unit with a starting action of its own.
   *
   * @param source what spawns
   * @param row the child's row
   * @param x the point along the width
   * @param y the point along the length
   */
  void spawnOne(CharacterEntity source, String row, int x, int y) {
    UnitData data = spawnedRow(row);
    if (data.building() || data.spawnPathfindSpeed() != 0 || data.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          source.name()
              + " spawns "
              + row
              + ", a building, a unit that paths to its point or one with a starting action,"
              + " which is not modelled");
    }
    int[] at =
        SpawnPlacement.position(
            x,
            y,
            0,
            1,
            true,
            0,
            (px, py) -> SpawnPassable.passable(tileMap, px, py, data.collisionRadius()));
    int cx = inset(at[0], tileMap.width());
    int cy = inset(at[1], tileMap.height());
    int count = spawnCounts.merge(source.name(), 1, Integer::sum) - 1;
    CharacterEntity child =
        CharacterEntity.spawned(
            this,
            data,
            source.name() + "_" + count,
            source.side(),
            cx,
            cy,
            PackedLevel.level(PackedLevel.pack(source.getPackedLevel(), data.rarity())));
    child.startDeploying();
    holder.add(child);
    if (DEATH_SPAWN_IMMUNE_FIRST_TICK) {
      child.startSpawnImmunity();
    }
    events.characterSpawned(source, child, cx, cy);
  }

  /**
   * The character an ability leaves on its unit's spot as it fires: one child of the row, on the
   * unit's position, or one unit right of it where the in-front test refuses the point, kept 250
   * inside the arena; created for the unit's side at its level re-based on the child's rarity,
   * deploying for its row's deploy time, untargetable at first, and queued with no registration
   * visit, so it joins the live list at the tick's closing cleanup and is first visited on the next
   * tick. A bomb - a building without hit points - dies at the end of that deploy.
   *
   * <p>Refused rather than guessed: a building with hit points, a unit that paths to its spawn
   * point or has a starting action of its own, and an object without hit points that has a range,
   * which the spawner pulls back by half of it.
   *
   * @param unit the unit whose ability fired
   * @param row the child's row
   */
  void activationSpawn(CharacterEntity unit, String row) {
    UnitData data = spawnedRow(row);
    if ((data.building() && data.hitpoints() > 0)
        || data.spawnPathfindSpeed() != 0
        || data.onStartingAction() != null
        || data.hitpoints() <= 0 && data.range() != 0) {
      throw new UnsupportedOperationException(
          unit.name()
              + "'s ability leaves "
              + row
              + ", a building with hit points, a unit that paths to its point or starts an action,"
              + " or an object without hit points with a range, which is not modelled");
    }
    int[] at =
        SpawnPlacement.position(
            unit.getView().getX(),
            unit.getView().getY(),
            0,
            1,
            true,
            0,
            (px, py) -> SpawnPassable.passable(tileMap, px, py, data.collisionRadius()));
    int x = inset(at[0], tileMap.width());
    int y = inset(at[1], tileMap.height());
    int count = spawnCounts.merge(unit.name(), 1, Integer::sum) - 1;
    CharacterEntity child =
        CharacterEntity.spawned(
            this,
            data,
            unit.name() + "_" + count,
            unit.side(),
            x,
            y,
            PackedLevel.level(PackedLevel.pack(unit.getPackedLevel(), data.rarity())));
    child.startDeploying();
    holder.add(child);
    if (DEATH_SPAWN_IMMUNE_FIRST_TICK) {
      child.startSpawnImmunity();
    }
    events.characterSpawned(unit, child, x, y);
  }

  /**
   * A projectile's flight step finding its target routing across the arena from its ability, under
   * a row that allows it: the target's reset hook, the same as entering a pathfinding state. The
   * damage on its way to the unit is set to 0, then every projectile aimed at it whose row allows
   * it loses it as its target, the one flying among them, handing nothing back; each flies on to
   * its aim, where the unit last stood for it, and lands on nothing. The switch's entry runs the
   * same hook, but a projectile launched in the step the unit starts across joins the battle after
   * it, so its own first flight step lets the unit go. A hook that would let its target go so,
   * whose flight goes on reading the target it let go, is refused.
   *
   * <p>A projectile aimed at the unit whose row keeps its target through the reset
   * (AllowResetTarget cleared) is left alone, as at a warp: it keeps the unit as its target,
   * follows it across and lands on it, its arrival handing back its own part of the emptied amount.
   * The rows that keep their target deal no damage, so that part is 0 and the hand-back changes
   * nothing. One with damage on its way, whose hand-back from the emptied amount no reference
   * holds, is refused.
   *
   * @param projectile the projectile whose flight step found its target routing across
   * @param unit its target
   */
  public void targetRoutingInFlight(ProjectileEntity projectile, CharacterEntity unit) {
    if (projectile.getData().dragBackSpeed() >= 1) {
      throw new UnsupportedOperationException(
          projectile.name() + " lets " + unit.name() + " go as it routes across, not modelled");
    }
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof ProjectileEntity p
          && p.getTarget() == unit
          && !p.getData().allowResetTarget()
          && p.registeredPendingAmount() != 0) {
        throw new UnsupportedOperationException(
            projectile.name()
                + " lets "
                + unit.name()
                + " go as it routes across with "
                + p.name()
                + " aimed at it, whose row keeps its target through the reset with damage on its"
                + " way, not modelled");
      }
    }
    unit.getView().setPendingDamageAmount(0);
    dropProjectilesAimedAt(unit);
  }

  /**
   * A unit going into either pathfinding state: every projectile of the live list aimed at it whose
   * row allows it loses it as its target, handing no damage back; it flies on to its aim and lands
   * on nothing.
   *
   * @param unit the unit
   */
  void pathfindEntered(CharacterEntity unit) {
    dropProjectilesAimedAt(unit);
  }

  /**
   * The drop of the unit's reset hook, which entering either pathfinding state and a warp's pending
   * damage reset run: every projectile of the live list aimed at the unit whose row allows it loses
   * it as its target, handing no damage back; it flies on to its aim and lands on nothing.
   *
   * @param unit the unit
   */
  private void dropProjectilesAimedAt(CharacterEntity unit) {
    List<String> dropped = new ArrayList<>();
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof ProjectileEntity p
          && p.getTarget() == unit
          && p.getData().allowResetTarget()) {
        p.dropTarget();
        dropped.add(p.name());
      }
    }
    if (!dropped.isEmpty()) {
      events.projectilesDropped(unit, dropped);
    }
  }

  /**
   * The area effect a resetable action makes: at the given point, for the unit's side, at its level
   * re-based on the area effect's rarity, the unit its parent and, for a row that follows its
   * parent, the object it follows; queued, so it joins the live list at the tick's closing cleanup
   * and first updates on the next tick. A row its creation refuses is refused here too.
   *
   * @param unit the unit the action runs on
   * @param row the area effect's row
   * @param x its point along the width
   * @param y its point along the length
   * @return the area effect
   */
  AreaEffectEntity resetableAreaEffect(CharacterEntity unit, String row, int x, int y) {
    if (unit.isClone()) {
      throw new UnsupportedOperationException(
          "a clone makes " + row + ", whose clone byte nothing the battle models reads");
    }
    AreaEffectData data = records.areaEffect(row);
    return createAreaEffect(
        row,
        x,
        y,
        unit.side(),
        unit.getPackedLevel(),
        null,
        "resetable",
        unit.name(),
        unit,
        data.followsParent() ? unit : null);
  }

  /**
   * The objects a shaped area effect lists: those in the rectangle about the point that pass the
   * filter for the area effect's team and row, in the index's bucket order, x outer and y inner,
   * each once. A building is tested by its square overlapping the rectangle, anything else by its
   * circle meeting it, both on live positions.
   *
   * @param owner the area effect
   * @param x the rectangle's centre along the width
   * @param y the rectangle's centre along the length
   * @param halfWidth half its width
   * @param halfHeight half its height
   * @param filter the filter
   * @return the objects, in the query's order
   */
  List<WorldEntity> rectangleQuery(
      AreaEffectEntity owner,
      int x,
      int y,
      int halfWidth,
      int halfHeight,
      GameObjectFilter filter) {
    return rectangleQuery(
        owner.side(), owner.getData().name(), x, y, halfWidth, halfHeight, filter);
  }

  /**
   * The objects in the rectangle about a point that pass the filter for the given side's team and
   * row name, as {@link #rectangleQuery(AreaEffectEntity, int, int, int, int, GameObjectFilter)}
   * lists them for an area effect.
   *
   * @param side the asking object's side
   * @param name the asking object's row name
   * @param x the rectangle's centre along the width
   * @param y the rectangle's centre along the length
   * @param halfWidth half its width
   * @param halfHeight half its height
   * @param filter the filter
   * @return the objects, in the query's order
   */
  List<WorldEntity> rectangleQuery(
      int side, String name, int x, int y, int halfWidth, int halfHeight, GameObjectFilter filter) {
    int team = side & 1;
    List<WorldEntity> out = new ArrayList<>();
    for (GridEntity view :
        index.boxQuery(
            x,
            y,
            halfWidth,
            halfHeight,
            v -> filter.matches(entityOf(v).filterSubject(), team, name))) {
      out.add(entityOf(view));
    }
    return out;
  }

  /**
   * The area effect an ability creates as it fires: at the unit's point, for its side, at its level
   * re-based on the area effect's rarity, the unit its parent and, for a row that follows its
   * parent, the object it follows; queued, so it joins the live list at the tick's closing cleanup
   * and first updates on the next tick. A row its creation refuses is refused here too.
   *
   * @param unit the unit whose ability fired
   * @param row the area effect's row
   * @return the area effect
   */
  AreaEffectEntity abilityAreaEffect(CharacterEntity unit, String row) {
    AreaEffectData data = records.areaEffect(row);
    return createAreaEffect(
        row,
        unit.getView().getX(),
        unit.getView().getY(),
        unit.side(),
        unit.getPackedLevel(),
        null,
        "ability",
        unit.name(),
        unit,
        data.followsParent() ? unit : null);
  }

  /**
   * The order an area effect's spawner takes its directions in: the numbers below the count, then
   * fifty pairs of draws of the battle's random source for each, at least one pair, each pair below
   * the count swapping the two entries it names.
   *
   * @param areaEffect the area effect
   * @param total how many directions its lifetime holds
   * @return the order
   */
  int[] spawnOrder(AreaEffectEntity areaEffect, int total) {
    int before = random.getState();
    int[] order = new int[total];
    for (int i = 0; i < total; i++) {
      order[i] = i;
    }
    for (int pair = Math.max(total * 50, 1); pair > 0; pair--) {
      int i = random.next(total);
      int j = random.next(total);
      if (i != j) {
        int kept = order[i];
        order[i] = order[j];
        order[j] = kept;
      }
    }
    events.spawnOrdered(areaEffect, order.clone(), before, random.getState());
    return order;
  }

  /**
   * One character of an area effect's spawner. It is placed at the direction and at SpawnMinRadius
   * and a draw of the battle's random source below what the radius leaves after its own collision
   * radius and that least distance, from the area effect's point; the point is moved off water and
   * 250 inside the arena by the relocation. A building of the live list whose circle overlaps the
   * character's, or a cell the standing test refuses, blocks the point, which is then drawn again
   * anywhere in the circle - a direction below 360 and a distance below the radius - up to five
   * times, the last point kept. The character is created there, kept 250 inside the arena, on the
   * area effect's side and at its level re-based on the character's rarity; it deploys for
   * SpawnTime, joins the holder with its registration visit, and becomes a clone for a row that
   * spawns clones.
   *
   * <p>Refused rather than guessed: a building, a unit that paths to its point or starts an action
   * of its own, and a row that does not spawn clones, whose children copy the area effect's clone
   * answer.
   *
   * @param areaEffect the area effect
   * @param angle the direction, in degrees
   * @param radius the radius of the area effect's hits now
   */
  void areaSpawn(AreaEffectEntity areaEffect, int angle, int radius) {
    AreaEffectData row = areaEffect.getData();
    UnitData data = spawnedRow(row.spawnCharacter());
    if (data.building()
        || data.spawnPathfindSpeed() != 0
        || data.onStartingAction() != null
        || !row.spawnClones()) {
      throw new UnsupportedOperationException(
          "the area effect "
              + areaEffect.name()
              + " spawns "
              + data.name()
              + ", a building, a unit that paths to its point or starts an action, or not as a"
              + " clone, which is not modelled");
    }
    int collision = data.collisionRadius();
    int distance = row.spawnMinRadius() + random.next(radius - collision - row.spawnMinRadius());
    int[] at = spawnPoint(areaEffect, angle, distance, collision);
    int retries = 0;
    while (at[2] != 0 && retries < 5) {
      angle = random.next(360);
      distance = random.next(radius);
      at = spawnPoint(areaEffect, angle, distance, collision);
      retries++;
    }
    int x = inset(at[0], tileMap.width());
    int y = inset(at[1], tileMap.height());
    int count = spawnCounts.merge(areaEffect.name(), 1, Integer::sum) - 1;
    CharacterEntity child =
        CharacterEntity.spawned(
            this,
            data,
            areaEffect.name() + "_" + count,
            areaEffect.side(),
            x,
            y,
            PackedLevel.level(PackedLevel.pack(areaEffect.packedLevel(), data.rarity())));
    if (row.spawnTimeMs() >= 1) {
      child.deployFor(row.spawnTimeMs());
    }
    holder.addRegistered(child);
    child.markClone(null);
    events.characterSpawned(areaEffect, child, x, y);
    events.areaSpawned(areaEffect, child, retries, random.getState());
  }

  /**
   * A spawner's point: at the direction and distance from the area effect's point, by the sine
   * table and its quarter turn, each over 1024 toward zero, then relocated off water and 250 inside
   * the arena; blocked by a building of the live list whose circle overlaps the given one, or by a
   * cell the standing test refuses.
   *
   * @return the point and 1 when it is blocked, else 0
   */
  private int[] spawnPoint(AreaEffectEntity areaEffect, int angle, int distance, int collision) {
    int x = areaEffect.x() + FixedMath.sine1024(angle) * distance / 1024;
    int y = areaEffect.y() + FixedMath.sine1024(angle + 90) * distance / 1024;
    int packed = Relocation.relocate(grid.getWidth(), grid.getHeight(), x, y, -1, grid::water);
    x = Relocation.unpackX(packed);
    y = Relocation.unpackY(packed);
    boolean blocked = buildingOver(x, y, collision) || (CellTests.cellBlocked(grid, x, y) & 1) != 0;
    return new int[] {x, y, blocked ? 1 : 0};
  }

  /** How many half-tile rows the building placement tries after a blocked point. */
  private static final int PLACEMENT_ROWS = 9;

  /**
   * The building placement a spawn row that validates its point as a building's asks for: the point
   * clamped into the arena, kept when no building of the live list other than the owner overlaps
   * the child's circle there and its cell can be stood on. The owner is the object whose action
   * runs the row, so a building that places a building where it stands, as the hero Musketeer's
   * dummy building places the turret, does not block its own point.
   *
   * <p>A blocked point is searched over the next nine half-tile rows of its column, one at a time,
   * stepping down the arena for side 0 and up for side 1 (the Goblins hero's banner dropped on the
   * river moves back onto its own side's bank). The child's footprint gives each row two edges,
   * half the footprint below and above it; a row passes when the cells at both edges can be stood
   * on and no building of the live list overlaps the child's circle at the middle of the two. This
   * overlap test leaves no object out, the owner included. The first passing row places the child
   * at the clamped x and at its lower edge moved a further footprint of rows along the side's way,
   * in whole half tiles.
   *
   * <p>When none of the nine rows passes, the search answers the requested point as it was asked
   * for, unclamped, and the child is spawned there as a row that does not validate its point would
   * spawn it: on a building's circle or a cell that cannot be stood on.
   *
   * @param owner the object whose action spawns the child
   * @param child the row of the child
   * @return the search
   */
  SpawnPerform.PlacementSearch buildingPlacement(WorldEntity owner, UnitData child) {
    return (x, y) -> {
      int clampedX = Math.max(0, Math.min(x, tileMap.width() * TileMap.CELL_UNITS - 1));
      int clampedY = Math.max(0, Math.min(y, tileMap.height() * TileMap.CELL_UNITS - 1));
      int radius = child.collisionRadius();
      int column = clampedX / TileMap.CELL_UNITS;
      int start = clampedY / TileMap.CELL_UNITS;
      if (!buildingOver(clampedX, clampedY, radius, owner) && cellStandable(column, start)) {
        return new int[] {clampedX, clampedY};
      }
      // Side 0 steps down the arena, side 1 up.
      boolean down = (owner.side() & 1) == 0;
      int footprint = PlacementSearch.footprint(child);
      for (int k = 1; k <= PLACEMENT_ROWS; k++) {
        int row = down ? start - k : start + k;
        int low = row - footprint / 2;
        int high = row + footprint / 2;
        if (!cellStandable(column, low) || !cellStandable(column, high)) {
          continue;
        }
        int middle = (low + high) / 2 * TileMap.CELL_UNITS;
        if (buildingOver(clampedX, middle, radius)) {
          continue;
        }
        return new int[] {clampedX, (low + (down ? -footprint : footprint)) * TileMap.CELL_UNITS};
      }
      return new int[] {x, y};
    };
  }

  /**
   * Whether a unit may stand on a half-tile cell: inside the arena, and neither not placeable,
   * blocked nor water. The standing test of a cell rather than of a point; a cell outside the arena
   * cannot be stood on.
   */
  private boolean cellStandable(int column, int row) {
    if (column < 0 || row < 0 || column >= grid.getWidth() || row >= grid.getHeight()) {
      return false;
    }
    return CellTests.cellBlocked(grid, column * TileMap.CELL_UNITS, row * TileMap.CELL_UNITS) == 0;
  }

  /**
   * Whether a building of the live list has a circle that overlaps a circle: its collision radius
   * and the given one together reach further than its centre lies from the point. The squares are
   * taken in 32 bits and compared unsigned; a square distance of the largest integer is passed by.
   */
  private boolean buildingOver(int x, int y, int radius) {
    return buildingOver(x, y, radius, null);
  }

  /**
   * Whether a building of the live list other than the one left out has a circle that overlaps a
   * circle, as {@link #buildingOver(int, int, int)} asks it.
   *
   * @param excluded the object the query passes by, or null to ask every building
   */
  private boolean buildingOver(int x, int y, int radius, BattleEntity excluded) {
    for (BattleEntity entity : holder.entities()) {
      if (entity == excluded
          || !(entity instanceof WorldEntity building)
          || !building.getTargetView().building()) {
        continue;
      }
      int dx = x - building.getView().getX();
      int dy = y - building.getView().getY();
      int squared = dx * dx + dy * dy;
      int reach = building.getView().getCollisionRadius() + radius;
      if (squared != Integer.MAX_VALUE && Integer.compareUnsigned(squared, reach * reach) < 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Hands a champion a spawn made to its side's champion slots: in a match the king's slot that
   * follows its row follows its play, or a slot takes it ({@link TowerEntity#championSpawned}). The
   * observers are told; nothing about the unit itself changes. A battle outside a match has no
   * slots, and only the observers hear of it.
   *
   * @param source the object the champion was spawned from
   * @param child the champion
   */
  void handOverChampion(SpawnHost source, SpawnHost child) {
    CharacterEntity champion = (CharacterEntity) child;
    TowerEntity king = kingTower(champion.side());
    if (king != null && king.championSlot(1) != null) {
      king.championSpawned(champion);
    }
    events.championHandedOver(source, champion);
  }

  /**
   * Whether a champion slot of a character's side follows it: the character is no clone and the
   * first or the second slot follows its row and its play's deploy count. Its hit points and
   * whether the slot's last working out listed it are not asked. Outside a match no slot exists and
   * no character is followed.
   *
   * @param unit the character
   */
  boolean followedChampion(CharacterEntity unit) {
    if (unit.isClone()) {
      return false;
    }
    TowerEntity king = kingTower(unit.side());
    if (king == null || king.championSlot(1) == null) {
      return false;
    }
    for (int slot = 1; slot <= 2; slot++) {
      ChampionController controller = king.championSlot(slot);
      UnitData followed = controller.getChampion();
      if (followed != null
          && followed.name().equals(unit.getData().name())
          && controller.getDeployIndex() == unit.getDeployIndex()) {
        return true;
      }
    }
    return false;
  }

  /**
   * The cooldown left on the champion slot of the unit's side that follows the unit, in
   * milliseconds; 0 when no slot follows it (a clone is followed by none).
   *
   * @param unit the unit
   */
  int championCooldownMs(CharacterEntity unit) {
    TowerEntity king = kingTower(unit.side());
    if (king == null || king.championSlot(1) == null) {
      return 0;
    }
    for (int n = 1; n <= 2; n++) {
      ChampionController slot = king.championSlot(n);
      if (slot != null && slot.follows(unit)) {
        return slot.getCooldownMs();
      }
    }
    return 0;
  }

  /**
   * Writes a button state override, and refills the charges when the row asks, into the slot of a
   * side that follows the row's champion; nothing when no slot follows it. The object the row runs
   * on decides only the side.
   *
   * @param side the side whose slots the row writes into
   * @param action the row
   */
  public void overrideAbilityButton(int side, OverrideAbilityButtonState action) {
    TowerEntity king = kingTower(side);
    if (king == null || king.championSlot(1) == null) {
      throw new UnsupportedOperationException(
          action.name() + " outside a match, which has no champion slots");
    }
    ChampionController slot = king.championSlotFollowing(action.getChampion());
    if (slot == null) {
      return;
    }
    if (action.getState() != 0) {
      slot.override(action.getState());
    }
    if (action.isResetCharges()) {
      slot.refillCharges();
    }
  }

  /**
   * The paths that make an area effect with no parent, as the game's own do: a spell's cast (and a
   * placement, which stands for one), a barrage's bomb, and the area effect another chains, whose
   * parent in the game is what the chaining one follows, none under the refusals of {@link
   * #createAreaEffect(String, int, int, int, int, String, String, String, SpawnHost, SpawnHost)}.
   * Every other path the battle core makes one on without a parent is one where the game hands it a
   * parent.
   */
  private static final Set<String> PARENTLESS_PATHS =
      Set.of("cast", "placed", "barrage", "chained");

  /**
   * Creates an area effect at a point and hands it to the holder, which gives it its id at once and
   * admits it at the next cleanup. It is named after its row and its id unless given a name.
   *
   * <p>Refused rather than guessed: a row that sets a column the area effect does not model, and
   * one that launches a projectile whose row sets a column its impact does not model.
   *
   * @param row the area effect's row
   * @param x its point along the width
   * @param y its point along the length
   * @param side its side
   * @param packedLevel the level it is created at, packed; re-based on its own rarity
   * @param name its name, or null for its row's and its id
   * @param how how it came about, for the observers
   * @param source the name of what it was created from, or null
   * @return the area effect
   */
  public AreaEffectEntity createAreaEffect(
      String row, int x, int y, int side, int packedLevel, String name, String how, String source) {
    return createAreaEffect(row, x, y, side, packedLevel, name, how, source, null, null);
  }

  /**
   * Creates an area effect as {@link #createAreaEffect(String, int, int, int, int, String, String,
   * String)} does, with a parent and an object it follows. A hit action that does not clone is
   * refused on an area effect no action made, and a row that follows its parent on one neither an
   * action nor an ability made: no other path that makes one is held. Refused too: a following row
   * that chains another area effect, which would follow what it follows, or whose buff attracts,
   * whose pull a moving area effect gates by an angle. And a row that ends with its parent's life
   * made with no parent on a path other than the {@link #PARENTLESS_PATHS}: a unit's death, a
   * direct hit, a projectile's impact, the evolved Dart Goblin's poison drop, where the game's area
   * effect has a parent to end with (the poison drop's is not settled) and the battle core's has
   * none.
   *
   * @param parent the object it keeps as its parent, or null for none
   * @param follow the object it follows, or null for none
   */
  private AreaEffectEntity createAreaEffect(
      String row,
      int x,
      int y,
      int side,
      int packedLevel,
      String name,
      String how,
      String source,
      SpawnHost parent,
      SpawnHost follow) {
    AreaEffectData data = records.areaEffect(row);
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          "the area effect " + row + " sets columns not modelled: " + data.unmodelledColumns());
    }
    // The filter form's hit pass schedules its hit action whatever made the area effect.
    if (data.onHitAction() != null
        && !data.filterHits()
        && !data.cloning()
        && !how.equals("action")
        && !how.equals("resetable")) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " has a hit action and was not made by an action, which is not modelled");
    }
    if (data.spawnCharacter() != null
        && (!how.equals("ability")
            || data.damage() != 0
            || data.buff() != null
            || data.onHitAction() != null
            || data.projectile() != null
            || data.spawnAreaEffectObject() != null
            || data.onStartingAction() != null
            || data.onLifeTimeEndAction() != null)) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " spawns characters and was not made by an ability, or hits, chains or runs an"
              + " action too, which is not modelled");
    }
    if (data.followsParent()
        && !how.equals("action")
        && !how.equals("ability")
        && !how.equals("resetable")) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " follows its parent and was not made by an action, which is not modelled");
    }
    // A row linked to its instigator's life ends as its parent leaves. The game gives the area
    // effect a parent on every path that makes one but a cast, a barrage's bomb and the area
    // another chains (whose parent is what the chaining one follows, which the refusals here keep
    // to none); a unit's death, a direct hit and a projectile's impact make theirs here with no
    // parent, so a linked row made there would never end with what made it. The poison drop's
    // parent in the game is not settled, so a linked row there is refused too.
    if (data.linkToInstigatorLife() && parent == null && !PARENTLESS_PATHS.contains(how)) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " ends with its parent's life (LinkToInstigatorLife) and was made by the "
              + how
              + " path, which gives it no parent here, which is not modelled");
    }
    if (data.followsTarget() && !how.equals("projectile")) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " follows a target and was not made by a projectile's impact, which is not"
              + " modelled");
    }
    if ((data.followsParent() || data.followsTarget()) && data.spawnAreaEffectObject() != null) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " follows its parent and chains an area effect, which is not modelled");
    }
    // The pull is handed the area effect's move since the start of the tick, and reads it only for
    // its angle test; without the test a following area effect pulls as one that stands still.
    if (data.buff() != null
        && buffData(data.buff()).attracts()
        && buffData(data.buff()).attractMaxAngle() >= 1) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " pulls only within an angle of its move, which is not modelled");
    }
    if (data.buff() != null) {
      buffData(data.buff());
    }
    if (data.projectile() != null) {
      ProjectileData launched = records.projectile(data.projectile());
      if (!launched.unmodelledColumns().isEmpty()) {
        throw new UnsupportedOperationException(
            "the area effect "
                + row
                + " launches "
                + launched.name()
                + ", which sets columns its impact does not model: "
                + launched.unmodelledColumns());
      }
    }
    AreaEffectEntity areaEffect =
        new AreaEffectEntity(
            this, data, side, x, y, PackedLevel.pack(packedLevel, data.rarity()), parent, follow);
    holder.add(areaEffect);
    areaEffect.setName(name != null ? name : row + "_" + areaEffect.getId());
    events.areaEffectCreated(areaEffect, how, source);
    return areaEffect;
  }

  /**
   * Creates the area effect an action's spawn row names: at the point of the holder's owner, moved
   * by the row's offsets - the one along the width as it stands, the one along the length turned
   * toward the far side of the owner's side - for the source's side and at its level, re-based on
   * the area effect's own rarity, the source kept as its parent; a row that follows its parent
   * follows the owner. It is handed to the holder in the pass that ran the action, so it is
   * admitted at that tick's closing cleanup and first updates on the next tick. The observers are
   * told after it is created.
   *
   * <p>It takes its source's clone answer: the area effect a clone's action spawns, as the trooper
   * a cloned hero Balloon drops makes as it lands or the one a cloned Goblinstein doctor starts
   * with, is a clone's, and so is one a clone's area effect spawns. Its hits read it for nothing:
   * it deals its damage and applies its buff as the original's would. A character an action spawns
   * from it is a clone (see {@link #cloneSpawn}); what else it would carry the mark on to is
   * refused (see {@link AreaEffectEntity#refuseClonesSpawn(String)}).
   *
   * @param owner the owner of the holder that ran the action
   * @param action the spawn row's name
   * @param row the area effect row's name
   * @param source the entity that caused the action
   * @param offsetX the row's offset along the width
   * @param offsetY the row's offset along the length, before the owner's side turns it
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the area effect
   */
  public AreaEffectEntity spawnAreaEffect(
      SpawnHost owner,
      String action,
      String row,
      SpawnHost source,
      int offsetX,
      int offsetY,
      int phase) {
    return spawnAreaEffect(
        owner, action, row, source, owner.x(), owner.y(), offsetX, offsetY, phase);
  }

  /**
   * Creates the area effect an action's spawn row names at a point - the owner's for the plain
   * class, the one its two expressions give for the location class - moved by the row's offsets,
   * the one along the length turned toward the far side of the owner's side; otherwise as above.
   *
   * @param owner the owner of the holder that ran the action
   * @param action the spawn row's name
   * @param row the area effect row's name
   * @param source the entity that caused the action
   * @param x the point along the width
   * @param y the point along the length
   * @param offsetX the row's offset along the width
   * @param offsetY the row's offset along the length, before the owner's side turns it
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the area effect
   */
  public AreaEffectEntity spawnAreaEffect(
      SpawnHost owner,
      String action,
      String row,
      SpawnHost source,
      int x,
      int y,
      int offsetX,
      int offsetY,
      int phase) {
    AreaEffectEntity areaEffect =
        createAreaEffect(
            row,
            x + offsetX,
            AreaEffectEntity.yDirection(owner.side()) * offsetY + y,
            source.side(),
            source.packedLevel(),
            null,
            "action",
            source.name(),
            source,
            records.areaEffect(row).followsParent() ? owner : null);
    // The spawn stamps it with its source's cast, as it takes its source's side and level.
    areaEffect.setCastId(source.castId());
    // The spawn sets the area effect's clone mark from its source's clone answer after handing it
    // to the holder.
    if (isClone(source)) {
      areaEffect.markClone();
    }
    events.areaEffectSpawned(owner, action, phase, source, areaEffect);
    return areaEffect;
  }

  /**
   * Launches the projectile an action's spawn row names from the owner's point, with the owner as
   * its launcher and owner, toward the point the row's two expressions give, each evaluated now on
   * the owner, the owner's own coordinate for one the row does not set, at no target; handed to the
   * holder. A row of the location class starts it at the row's start height; a row of the plain
   * class at the row's start height above the owner's live height. A row of either class aimed by
   * neither expression launches at the owner's current target, which is refused unless the owner is
   * a character whose targeting component is off or holds nothing, or whose row reads no target
   * (see {@link #readsLaunchTarget(ProjectileData, WorldEntity)}).
   *
   * @param owner the entity the action runs on
   * @param action the spawn row's name
   * @param row the projectile row's name
   * @param startHeight the row's start height
   * @param aimX the aim along the arena's width, or null for the owner's own coordinate
   * @param aimY the aim along the arena's length, or null for the owner's own coordinate
   * @param spawnClass true for a row of the plain spawn class, false for the location class
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  void actionProjectile(
      WorldEntity owner,
      String action,
      String row,
      int startHeight,
      IntSupplier aimX,
      IntSupplier aimY,
      boolean spawnClass,
      boolean fromContext,
      Integer targetId,
      int startOffset,
      int phase) {
    if (owner instanceof CharacterEntity unit && unit.isClone()) {
      throw new UnsupportedOperationException(
          action + " launches " + row + " from a clone, which is not modelled");
    }
    // Either class aimed by neither expression launches at the owner's current target, read
    // before the start; an expression drops it. A dying unit's combat gate has switched its
    // targeting off by the time its killed action runs if it died before its state visit, so it
    // launches at none. One that died at the damage drain, after its visit, still holds its
    // target; a row that reads none launches the same either way. A row that names its target in
    // the context launches at that object, the live one of the id it read, and keeps it whatever
    // its expressions.
    ProjectileData data = records.projectile(row);
    if (!fromContext
        && aimX == null
        && aimY == null
        && (!(owner instanceof CharacterEntity unit)
            || unit.referenceHeld() && readsLaunchTarget(data, unit.currentTarget()))) {
      throw new UnsupportedOperationException(
          action
              + " launches "
              + row
              + " at the current target of "
              + owner.name()
              + ", which is held or not a character's, not modelled");
    }
    int sx = owner.getView().getX();
    int sy = owner.getView().getY();
    // The plain class's height slot is the owner's live height; the location class's answers 0.
    int sz = startHeight + (spawnClass ? owner.getView().getZTotal() : 0);
    // The width's expression is evaluated first, then the length's.
    int hx = aimX == null ? sx : aimX.getAsInt();
    int hy = aimY == null ? sy : aimY.getAsInt();
    WorldEntity target =
        fromContext && targetId != null ? (WorldEntity) liveObject(targetId) : null;
    // With a start offset and a target the start moves that far toward the target, after the
    // aim took the owner's point as its default.
    if (startOffset != 0 && target != null) {
      int[] toward = {target.getView().getX() - sx, target.getView().getY() - sy};
      FixedMath.normalize(toward, startOffset);
      sx += toward[0];
      sy += toward[1];
    }
    ProjectileEntity projectile = new ProjectileEntity(this, data, owner.side());
    ProjectileLauncher.launchFromAction(projectile, owner, target, sx, sy, sz, hx, hy);
    launch(projectile);
    events.actionProjectileLaunched(owner, action, phase, projectile);
  }

  /**
   * Whether a projectile row launched at a target reads it. One that does not home flies to its
   * aim, and with a radius of at least 1 its impact is the area at its impact point; the target
   * would only take the row's on-hit target action, its target buff or its pushback, and be chained
   * from. A row with none of these reads no target, so a launch at one is a launch at none. The
   * pushback moves only a target with a movement component: a launch at a building or a tower, as
   * the hero Dark Prince's mount, which targets only buildings, launches its landing blow, pushes
   * nothing there.
   *
   * @param data the projectile row
   * @param target the target it would be launched at, or null for none
   */
  private static boolean readsLaunchTarget(ProjectileData data, WorldEntity target) {
    return data.homing()
        || data.radius() < 1
        || data.onHitTargetAction() != null
        || data.targetBuff() != null
        || data.pushback() > 0 && (target == null || target.hasMovementComponent())
        || data.chainedHitRadius() > 0;
  }

  /**
   * A strong hit of the axe on its way out asks for a push of its target away from the axe's start:
   * of a character whose movement component is on, with every gate in place; the observers are told
   * first.
   */
  public void axePush(
      ProjectileEntity axe, WorldEntity target, int x, int y, int distance, int hitId) {
    if (!(target instanceof CharacterEntity character) || !character.movementOn()) {
      return;
    }
    events.axePushed(axe, target, x, y, distance, hitId);
    character.pushedFrom(x, y, distance);
  }

  /** The not-placeable value alone, which a cell blocks a roll's destination with. */
  private static final int ROLL_BLOCKING_CELL = TileMap.NOT_PLACEABLE_BIT;

  /**
   * The tags a capture's drag raises on its unit for one step. NO_REFLECTED_ATTACK is not among
   * them: the drag raises no tag a reflect reads, and a reflect tests no tag of the unit hit. A
   * captured unit that reflects is stopped only as any reflect is, here by its capture buff's hit
   * speed of none, so it strikes nothing back while held.
   */
  long captureTags() {
    return flagBits.noDash()
        | flagBits.buildingDeathSpawnFindLocation()
        | flagBits.abilityDisabled()
        | flagBits.captured();
  }

  /** The tags a capture's drag raises on its unit for one step besides, without a capture buff. */
  long captureHoldTags() {
    return flagBits.noMove() | flagBits.noSummon() | flagBits.noAttack();
  }

  /**
   * The deflection pass a roll runs at its projectile's point and height as it starts and after
   * each step: the deflection pass of a flying projectile (see {@link
   * ProjectileDeflection#deflectPass}), whose deflection of a row with its own deflection action,
   * as the evolved Snowball's rolling snowball, schedules that action on the projectile in place of
   * turning it (see {@link ProjectileEntity#deflectAt}); the roll reads the count it raises at its
   * next step. Refused beside a deflecting area effect: a rolling row without such an action, whose
   * redirect would launch the projectile again under its roll, which no row of the data does and no
   * recording holds.
   *
   * @param projectile the rolling projectile
   */
  public void rollDeflectionPass(ProjectileEntity projectile) {
    if (projectile.getData().customDeflectAction() == null && !deflection.deflectors().isEmpty()) {
      throw new UnsupportedOperationException(
          projectile.name()
              + " rolls beside a deflecting area effect without its own deflection action, which"
              + " is not modelled");
    }
    deflection.deflectPass(projectile, projectile.getX(), projectile.getY(), projectile.getZ());
  }

  /**
   * Whether a roll's destination is blocked: off the map, or with a cell of the not-placeable value
   * alone in the two by two block of its tile.
   *
   * @param x the point along the width
   * @param y the point along the length
   */
  public boolean rollBlocked(int x, int y) {
    if ((x | y) < 0 || x >= tileMap.widthUnits() || y >= tileMap.heightUnits()) {
      return true;
    }
    int col = (x / TileMap.CELL_UNITS) & ~1;
    int row = (y / TileMap.CELL_UNITS) & ~1;
    for (int j = 0; j < 2; j++) {
      for (int i = 0; i < 2; i++) {
        if (grid.tiles(col + i, row + j) == ROLL_BLOCKING_CELL) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The query a roll runs around its projectile: the object query at the projectile's point,
   * testing a building by its square, the filter asked for the projectile's side and row name.
   *
   * @param projectile the rolling projectile
   * @param radius the circle's radius
   * @param filter the filter row
   */
  public List<WorldEntity> rollQuery(
      ProjectileEntity projectile, int radius, GameObjectFilter filter) {
    return objectQuery(
        projectile.getX(),
        projectile.getY(),
        projectile.side(),
        projectile.getData().name(),
        radius,
        filter,
        true);
  }

  /**
   * A roll's buff on what it found: with no parent, the projectile its source, at its level and for
   * its side.
   *
   * @param projectile the rolling projectile
   * @param target what it found
   * @param buff the buff's row name
   * @param timeMs how long it lasts
   */
  public void rollBuff(ProjectileEntity projectile, WorldEntity target, String buff, int timeMs) {
    events.rollBuffed(projectile, target, buff, timeMs);
    target
        .getBuffs()
        .apply(
            records.buff(buff), timeMs, projectile.getPackedLevel(), projectile, projectile.side());
  }

  /** Whether an object with an id is in the live list and alive, as a lock's holder is tested. */
  public boolean listedAndAlive(int id) {
    return listedAlive(id);
  }

  /** The entity with an id in the live list, or null for none or for another kind of object. */
  public WorldEntity liveEntity(int id) {
    return liveObject(id) instanceof WorldEntity entity ? entity : null;
  }

  /**
   * The query a capture runs around its projectile: the object query at the projectile's point, the
   * filter asked for the projectile's side and row name.
   *
   * @param projectile the capturing projectile
   * @param radius the circle's radius
   * @param filter the filter row
   */
  public List<WorldEntity> captureQuery(
      ProjectileEntity projectile, int radius, GameObjectFilter filter) {
    return objectQuery(
        projectile.getX(),
        projectile.getY(),
        projectile.side(),
        projectile.getData().name(),
        radius,
        filter,
        false);
  }

  /** Whether a claimed unit still passes a capture's filter, asked for the projectile's side. */
  public boolean capturePasses(
      ProjectileEntity projectile, WorldEntity unit, GameObjectFilter filter) {
    return filter.matches(unit.filterSubject(), projectile.side() & 1, projectile.getData().name());
  }

  /**
   * A capture's buff on a unit it captured: the capture's owner, the evolved Snowball's rolling
   * projectile or the evolved Goblin Cage, its parent and source, at the owner's level and for its
   * side. The buff stacks, so the instance keeps the owner as its parent and leaves with it.
   *
   * @param owner the object the capture runs on
   * @param unit the unit captured
   * @param buff the capture row's buff
   * @param timeMs how long it lasts
   */
  public <T extends BattleEntity & SpawnHost> void captureBuff(
      T owner, WorldEntity unit, String buff, int timeMs) {
    unit.getBuffs()
        .apply(records.buff(buff), timeMs, owner.packedLevel(), owner, owner.side(), owner);
  }

  /** Whether a unit's tag word holds the hidden tag. */
  public boolean taggedHidden(WorldEntity unit) {
    return (unit.getView().getFlags() & unit.getView().getFlagBits().hidden()) != 0;
  }

  /**
   * The capture's tags on a unit it drags, for one step, with NO_MOVE, NO_SUMMON and NO_ATTACK
   * besides for a capture without a buff. BUILDING_DEATH_SPAWN_FIND_LOCATION is raised and read by
   * nothing else: the death spawn of a captured unit, a building included, is put where any death
   * spawn is, on the unit's own point for one child. A reflecting unit, the one captured or any
   * other, is untouched by the capture's tags (see {@link #captureTags()}). Refused: a unit with a
   * spawner held without a buff, whose reader of NO_SUMMON is not modelled.
   *
   * @param unit the unit dragged
   * @param withoutBuff true for a capture whose row gives no capture buff
   */
  public void captureTagged(WorldEntity unit, boolean withoutBuff) {
    if (withoutBuff && unit.getData().spawnCharacter() != null) {
      throw new UnsupportedOperationException(
          unit.name() + " is captured without a buff and has a spawner, not modelled");
    }
    unit.raiseCaptureTags(withoutBuff ? captureTags() | captureHoldTags() : captureTags());
  }

  /**
   * A capture's drag step during its pause: the unit only turned to the owner's point.
   *
   * @param owner the capturing owner's row name
   * @param unit the unit held
   * @param toX the owner's point it turns to, along the width
   * @param toY the owner's point it turns to, along the length
   */
  public void captureFaced(String owner, WorldEntity unit, int toX, int toY) {
    captured(owner, unit).faceToward(toX, toY);
  }

  /**
   * A capture's hit on a unit it holds: the damage entry with no hit id and no dedupe id, the
   * capturing character the attacker, passing the hidden unit. The entry queues it for the damage
   * drain, after the tick's post-hooks, as it queues a direct hit (see {@link #queuedHits}); a hit
   * that kills runs the unit's death there, the capturing character its killer, so the unit's death
   * slot runs - its death damage, its death spawn, a building included, and its area effect - and
   * its death handler after it.
   *
   * @param owner the capturing character
   * @param unit the unit held
   * @param damage the damage per hit at the owner's level
   */
  public void captureHit(CharacterEntity owner, WorldEntity unit, int damage) {
    queuedHits.add(new CaptureHitDue(owner, unit, damage));
  }

  /**
   * A capture's drag step: the unit moved toward the owner's point, then turned to it.
   *
   * @param owner the capturing owner's row name
   * @param unit the unit dragged
   * @param x where it is moved, along the width
   * @param y where it is moved, along the length
   * @param toX the owner's point it turns to, along the width
   * @param toY the owner's point it turns to, along the length
   */
  public void captureDragged(String owner, WorldEntity unit, int x, int y, int toX, int toY) {
    CharacterEntity character = captured(owner, unit);
    character.warpTo(x, y);
    character.faceToward(toX, toY);
  }

  /**
   * A completed drag: the unit put on the owner's point and, with a movement component, put down
   * when it is in a jump or dashing under a row with a jump height (on the ground and asked to
   * stand, which ends the jump or the dash, as a hook puts down the unit it hooks), its route reset
   * and, with a height change, the change pushed with its floor, which the next pre-hook folds.
   *
   * @param owner the capturing owner's row name
   * @param heightModifier the capture's height change, 0 for none
   * @param heightModifierCap the change's floor
   */
  public void capturePutOn(
      String owner, WorldEntity unit, int x, int y, int heightModifier, int heightModifierCap) {
    CharacterEntity character = captured(owner, unit);
    character.warpTo(x, y);
    if (character.hasMovementComponent()) {
      if (inTheAir(unit)) {
        unit.getView().setZ(0);
        character.requestState(GridEntityState.STANDING);
      }
      // The put-down's route reset and the drag's own are the same reset.
      character.resetRoute();
      if (heightModifier != 0) {
        character.startLayering();
        character.pushHeight(heightModifier, heightModifierCap);
      }
    }
  }

  /** A captured object as the character it must be. */
  private static CharacterEntity captured(String owner, WorldEntity unit) {
    if (!(unit instanceof CharacterEntity character)) {
      throw new UnsupportedOperationException(
          owner + " captures " + unit.name() + ", not a character, not modelled");
    }
    return character;
  }

  /**
   * Makes one bomb's area effect of a barrage: its row at the point, for the owner's side and at
   * its level, re-based on the area effect's rarity, with no parent, handed to the holder, which
   * admits it at the tick's closing cleanup.
   *
   * @param owner the character running the barrage
   * @param row the area effect row
   * @param x its point along the width
   * @param y its point along the length
   * @return the area effect
   */
  AreaEffectEntity barrageAreaEffect(CharacterEntity owner, String row, int x, int y) {
    AreaEffectEntity areaEffect =
        createAreaEffect(
            row, x, y, owner.side(), owner.getPackedLevel(), null, "barrage", owner.name());
    // The barrage stamps it with its character's cast.
    areaEffect.setCastId(owner.castId());
    return areaEffect;
  }

  /**
   * Drops a barrage's bomb onto the area effect that caused the drop: its projectile from the area
   * effect's point at the row's height, with the area effect as its launcher, side and level, aimed
   * at the same point, at the speed that lands it over the area effect's lifetime - the height over
   * the lifetime's steps of 50 ms, 0 for a lifetime under one step; handed to the holder.
   *
   * @param area the area effect
   * @param action the drop
   * @param phase the phase of the pending pass that ran the drop
   */
  void cannonBomb(AreaEffectEntity area, CannonProjectileSpawn action, int phase) {
    area.refuseClonesSpawn("drops " + action.getProjectile());
    int lifetime =
        area.getLifetimeOverride() >= 0
            ? area.getLifetimeOverride()
            : area.getData().lifeDurationMs();
    int steps = lifetime / StateQueries.TICK_MS;
    int speed = steps == 0 ? 0 : action.getHeight() / steps;
    ProjectileEntity projectile =
        new ProjectileEntity(this, records.projectile(action.getProjectile()), area.side());
    projectile.dropOnto(area, action.getHeight(), speed);
    launch(projectile);
    events.bombDropped(area, action.name(), phase, projectile, lifetime);
  }

  /** Counts one more character made, which a summon's name carries. */
  void characterMade() {
    charactersMade++;
  }

  /**
   * Makes an area effect of an evolved Royal Ghost's run at a point: for the Ghost's side and at
   * its level, re-based on the area effect's own rarity, the Ghost kept as its parent and following
   * nothing, handed to the holder, which admits it at the tick's closing cleanup. A row that
   * follows its parent is refused: the object the run hands such a row is not established.
   *
   * @param ghost the Ghost
   * @param row the area effect's row
   * @param x the point along the width
   * @param y the point along the length
   * @param target the reference the run kept, which only a following row would read, or null
   * @return the area effect
   */
  AreaEffectEntity ghostArea(CharacterEntity ghost, String row, int x, int y, WorldEntity target) {
    if (records.areaEffect(row).followsParent()) {
      throw new UnsupportedOperationException(
          ghost.name() + " makes " + row + ", which follows its parent, not modelled");
    }
    AreaEffectEntity area =
        createAreaEffect(
            row,
            x,
            y,
            ghost.side(),
            ghost.getPackedLevel(),
            null,
            "ghost_evo",
            ghost.name(),
            ghost,
            null);
    events.ghostAreaMade(ghost, area, target);
    return area;
  }

  /**
   * Makes a container a balloon pop drops at a point: for the character's side and at its level,
   * re-based on the area effect's own rarity, the character kept as its parent, alive or just dead,
   * and following nothing; handed to the holder in the pass that ran the pop, so it is admitted at
   * that tick's closing cleanup and first updates on the next tick. Refused: a clone, whose clone
   * byte the container would carry, and a row that follows its parent.
   *
   * @param owner the character the pop runs on
   * @param action the pop row's name
   * @param row the container's area effect row
   * @param x the point along the width
   * @param y the point along the length
   * @return the area effect
   */
  AreaEffectEntity containerAreaEffect(
      CharacterEntity owner, String action, String row, int x, int y) {
    if (owner.isClone()) {
      throw new UnsupportedOperationException(
          "a clone's " + action + " drops " + row + ", whose clone byte nothing modelled reads");
    }
    if (records.areaEffect(row).followsParent()) {
      throw new UnsupportedOperationException(
          action + " drops " + row + ", which follows its parent, not modelled");
    }
    return createAreaEffect(
        row,
        x,
        y,
        owner.side(),
        owner.getPackedLevel(),
        null,
        "pop_balloon",
        owner.name(),
        owner,
        null);
  }

  /**
   * A summon area's summon: one child of the row on the area's point, or one unit right of it where
   * the in-front test refuses the point, kept 250 inside the arena; created for the area's side at
   * its level re-based on the child's rarity and deploying for its row's deploy time, named after
   * its row and the count of characters made before it. It takes the reference handed over when
   * that is alive and its validator accepts it, faces the point it was summoned toward, is queued
   * with no registration visit, so it joins the live list at the tick's closing cleanup, and runs
   * the combat gate.
   *
   * <p>Refused rather than guessed: a building, a unit that paths to its spawn point or limits its
   * group, and a unit with a starting action that is not presentation alone, which a child starts
   * as it joins the live list; a presentation row is left out.
   *
   * @param area the summon area
   * @param row the child's row
   * @param reference the reference handed over, or null for none
   * @param x the point it faces along the width
   * @param y the point it faces along the length
   */
  void ghostSummon(AreaEffectEntity area, String row, WorldEntity reference, int x, int y) {
    area.refuseClonesSpawn("summons " + row);
    UnitData data = spawnedRow(row);
    if (data.building()
        || data.spawnPathfindSpeed() != 0
        || data.onStartingAction() != null && !actions.playsEffect(data.onStartingAction())) {
      throw new UnsupportedOperationException(
          area.name()
              + " summons "
              + row
              + ", a building, a unit that paths to its point or one with a starting action,"
              + " which is not modelled");
    }
    int[] at =
        SpawnPlacement.position(
            area.getX(),
            area.getY(),
            0,
            1,
            true,
            0,
            (px, py) -> SpawnPassable.passable(tileMap, px, py, data.collisionRadius()));
    int cx = inset(at[0], tileMap.width());
    int cy = inset(at[1], tileMap.height());
    CharacterEntity child =
        CharacterEntity.spawned(
            this,
            data,
            row + "_" + charactersMade,
            area.side(),
            cx,
            cy,
            PackedLevel.level(PackedLevel.pack(area.packedLevel(), data.rarity())));
    child.startDeploying();
    holder.add(child);
    events.ghostSummonMade(area, child);
    child.takeSummonReference(reference);
    child.faceToward(x, y);
    child.summonReveal();
    events.ghostSummonSpawned(area, child);
  }

  /**
   * Makes a target indicator attack's signal: the area effect of the row at the target's point, for
   * the unit's side and at its level, re-based on the area effect's own rarity, the unit kept as
   * its parent and following nothing, handed to the holder with the unit's id filed beside it. It
   * is admitted at the tick's closing cleanup and first updates on the next tick, while the unit is
   * still listed and not removable then; a unit that leaves in that cleanup, killed in the tick the
   * signal was made, has its signal let go by the fold instead, never admitted, its id spent.
   *
   * @param unit the unit running the attack
   * @param row the area effect's row
   * @param target what it marks
   * @return the signal
   */
  AreaEffectEntity indicate(CharacterEntity unit, String row, WorldEntity target) {
    GridEntity at = target.getView();
    AreaEffectEntity signal =
        createAreaEffect(
            row,
            at.getX(),
            at.getY(),
            unit.side(),
            unit.packedLevel(),
            null,
            "target_indicator",
            unit.name(),
            unit,
            null);
    holder.requireListed(signal, unit.getId());
    return signal;
  }

  /**
   * Launches a target indicator attack's projectile at its signal, from the start, with the unit as
   * launcher and owner, handed to the holder.
   *
   * @param unit the unit running the attack
   * @param row the projectile's row
   * @param signal the signal it is fired at
   * @param sx start position along the arena's width
   * @param sy start position along the arena's length
   * @param sz start height
   * @return the projectile
   */
  ProjectileEntity launchAtSignal(
      CharacterEntity unit, String row, AreaEffectEntity signal, int sx, int sy, int sz) {
    ProjectileEntity projectile = new ProjectileEntity(this, records.projectile(row), unit.side());
    projectile.launchAtSignal(unit, signal, sx, sy, sz);
    launch(projectile);
    return projectile;
  }

  /** A buff's row, refused when it sets a column the battle does not model. */
  BuffData buffData(String name) {
    BuffData buff = records.buff(name);
    if (!buff.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          "the buff " + name + " sets columns not modelled: " + buff.unmodelledColumns());
    }
    return buff;
  }

  /**
   * An area effect's buff, applied with one of its hits: to each character of this tick, in the
   * order they joined, inside its circle and reached by it; of the king-class towers only the first
   * takes a buff that deals damage. Each is applied with the area effect as the source, and as the
   * parent of a buff its parent controls, at its level and for its side, once every target has been
   * found.
   *
   * @param areaEffect the area effect
   * @param radius the radius of its hit
   * @param time how long the buff lasts
   */
  void areaBuff(AreaEffectEntity areaEffect, int radius, int time) {
    BuffData buff = buffData(areaEffect.getData().buff());
    List<WorldEntity> targets =
        buffTargets(
            areaEffect.getX(),
            areaEffect.getY(),
            radius,
            buff,
            AREA_EFFECT_BUFF_LIMIT,
            areaEffect::buffReaches);
    events.areaBuff(areaEffect, buff, time, targets);
    // A buff its parent controls has the area effect as its parent too.
    AreaEffectEntity parent = buff.controlledByParent() ? areaEffect : null;
    for (WorldEntity target : targets) {
      target
          .getBuffs()
          .apply(buff, time, areaEffect.getPackedLevel(), areaEffect, areaEffect.side(), parent);
    }
  }

  /**
   * A projectile's target buff on the circle of its impact: to each character of this tick, in the
   * order they joined, inside the circle around the impact point and reached by it, while the row's
   * target limit lasts; of the king-class towers only the first takes a buff that deals damage.
   * Each is applied with the projectile as the source, at its level and for its side, for the row's
   * buff time at that level, once every target has been found.
   *
   * <p>The projectile reaches a character of the launcher's side only when the row does not hit
   * enemies only, and one of the other side only when it does not buff its own troops only; the
   * character must be alive, not untouchable, not hidden and not waiting to deploy. The pass does
   * not reach hidden units, so a unit the evolved Goblin Cage holds hidden on its point is passed
   * by, as the impact's damage passes it by. Unlike an area effect it has no filter of its own, so
   * the air and the ground are reached alike.
   *
   * @param projectile the projectile that landed
   * @param x the impact point along the width
   * @param y the impact point along the length
   */
  public void projectileAreaBuff(ProjectileEntity projectile, int x, int y) {
    ProjectileData data = projectile.getData();
    BuffData buff = buffData(data.targetBuff());
    int time = data.buffTime(projectile.getPackedLevel());
    List<WorldEntity> targets =
        buffTargets(
            x,
            y,
            data.radius(),
            buff,
            data.maximumTargets(),
            target -> projectileBuffReaches(projectile, target));
    events.projectileBuff(projectile, buff, time, targets);
    for (WorldEntity target : targets) {
      target
          .getBuffs()
          .apply(buff, time, projectile.getPackedLevel(), projectile, projectile.side());
    }
  }

  /**
   * A unit's buff while it is not attacking, applied to itself: from the unit, at its level and for
   * its side, for the given time, as its creation and its state visit's countdown apply it.
   *
   * @param unit the unit
   * @param time how long the buff lasts
   */
  /**
   * A unit's starting buff, which the level setter's tail applies as the unit is made, ahead of its
   * buff while not attacking: from the unit itself, at its level and for its side, with no parent,
   * for its row's StartingBuffTime.
   *
   * @param unit the unit being made
   */
  void startingBuff(CharacterEntity unit) {
    UnitData data = unit.getData();
    unit.getBuffs()
        .apply(
            buffData(data.startingBuff()),
            data.startingBuffTimeMs(),
            unit.getPackedLevel(),
            unit,
            unit.side());
  }

  void notAttackingBuff(CharacterEntity unit, int time) {
    BuffData buff = buffData(unit.getData().buffWhenNotAttacking());
    unit.getBuffs().apply(buff, time, unit.getPackedLevel(), unit, unit.side());
  }

  /**
   * A queued direct hit's buff on damage, as the drain deals the hit: applied right after the
   * damage, the hit's death and its reflect, when the damage entry let the hit through (the drain
   * applies it only for a record its bookkeeping accepted), a hit that kills included; nothing for
   * an attacker whose row sets none, or a target that has left the battle. The bookkeeping accepts
   * a hit after the battle's end too, which takes nothing; the drain would apply the buff on damage
   * of such a hit as well, which no reference holds, so it is refused. A hit with no attacker
   * carries none.
   *
   * <p>The buff and its time are read here, at the drain, from the attacker as it is then: its
   * attack sequence entry's CustomBuffOnDamage and CustomBuffOnDamageTime where it has them (see
   * {@link WorldEntity#buffOnDamageAtDrain()}), else its row's. No buff, or a time of 0, applies
   * nothing.
   *
   * @param attacker the entity whose hit it is, or null for none
   * @param target the view the hit resolved against
   * @param result what the hit's damage did
   */
  private void drainBuffOnDamage(WorldEntity attacker, TargetView target, DamageResult result) {
    if (!result.accepted()
        || attacker == null
        || attacker.buffOnDamageAtDrain() == null
        || attacker.buffOnDamageTimeAtDrain() == 0) {
      return;
    }
    if (!result.landed()) {
      throw new UnsupportedOperationException(
          attacker.name()
              + " lands a direct hit with a BuffOnDamage after the battle's end, which is not"
              + " modelled");
    }
    WorldEntity entity = known.get(target.getEntity());
    if (entity != null) {
      buffOnDamage(attacker, entity);
    }
  }

  /**
   * A hit's buff on damage on what it reached, after its direct hit: nothing for a target
   * untouchable at that moment, the immunity left after a dash counted; otherwise applied with the
   * attacker as the source, at its level and for its side, for its BuffOnDamageTime. The buff and
   * the time are the attacker's attack sequence entry's where it has them, else its row's (see
   * {@link WorldEntity#buffOnDamageAtDrain()}). There is no alive test, so a target the hit has
   * just killed takes it too and leaves at the tick's closing cleanup; the buff decides for itself
   * whether the target takes it. A second hit, of the same attack or another attacker, refreshes
   * the one instance.
   *
   * @param attacker the entity whose hit it is
   * @param target what the hit reached
   */
  void buffOnDamage(WorldEntity attacker, WorldEntity target) {
    UnitData data = attacker.getData();
    BuffData buff = buffData(attacker.buffOnDamageAtDrain());
    // A hit applies its buff with the attacker as its parent, which a stacking buff keeps: its
    // instance would leave with the attacker and bar another from the same parent. No row does.
    if (buff.enableStacking()) {
      throw new UnsupportedOperationException(
          data.name() + " applies a stacking BuffOnDamage kept with its parent, not modelled");
    }
    if (!target.untouchable()) {
      target
          .getBuffs()
          .apply(
              buff,
              attacker.buffOnDamageTimeAtDrain(),
              attacker.getPackedLevel(),
              attacker,
              attacker.side());
    }
  }

  /**
   * A queued projectile hit's target buff, as the drain deals the hit: right after the damage, the
   * hit's death and its reflect, to the victim of that one hit, for a projectile whose row sets a
   * target buff and does not apply it before the damage, when the damage entry let the hit through
   * - a hit that kills included, and one after the battle's end, which the bookkeeping accepts
   * though it takes nothing. It reaches the victims of an area impact one by one, as the drain
   * deals their shares, so only what the area damaged takes it. A deflection's hit on the
   * deflector's parent takes it too: the projectile is that hit's source, though it flies on to its
   * new target, so the Monk takes the slow or the stun of a shot it turns. Applied with the
   * projectile as the source, at its level and for its side, for the row's buff time at that level;
   * the drain asks nothing of the victim's dash immunity, which the damage entry has answered
   * already.
   *
   * <p>Being applied after every post-hook of the tick, the buff is first read by the victim's
   * combat gate on the next tick: a stun leaves the victim its targeting visit of that tick, under
   * a hit speed scaled to nothing, and drops its reference at that tick's gate.
   *
   * @param projectile the projectile whose hit it is
   * @param victim the entity the hit reached
   * @param result what the hit's damage did
   */
  private void drainProjectileBuff(
      ProjectileEntity projectile, WorldEntity victim, DamageResult result) {
    ProjectileData data = projectile.getData();
    if (!result.accepted() || data.targetBuff() == null || data.applyBuffBeforeDamage()) {
      return;
    }
    if (known.get(victim.getView()) != victim) {
      return;
    }
    BuffData buff = buffData(data.targetBuff());
    int time = data.buffTime(projectile.getPackedLevel());
    events.projectileBuff(projectile, buff, time, List.of(victim));
    victim.getBuffs().apply(buff, time, projectile.getPackedLevel(), projectile, projectile.side());
  }

  /**
   * A projectile's target buff on its one target: nothing for a target untouchable at that moment -
   * riding, or dashing under a dash immunity, but not the immunity that lingers after a dash -
   * unless the row applies it even then; otherwise applied with the projectile as the source, at
   * its level and for its side, for the row's buff time at that level. There is no alive test, so a
   * target the damage has just killed takes it too; the buff decides for itself whether the target
   * takes it, so a building takes nothing of one that ignores buildings.
   *
   * @param projectile the projectile that landed
   * @param target its target
   */
  public void projectileTargetBuff(ProjectileEntity projectile, WorldEntity target) {
    ProjectileData data = projectile.getData();
    BuffData buff = buffData(data.targetBuff());
    int time = data.buffTime(projectile.getPackedLevel());
    List<WorldEntity> targets = List.of();
    if (data.applyBuffEvenIfImmuneToDamage() || !target.untouchable(false)) {
      target
          .getBuffs()
          .apply(buff, time, projectile.getPackedLevel(), projectile, projectile.side());
      targets = List.of(target);
    }
    events.projectileBuff(projectile, buff, time, targets);
  }

  /**
   * Where a hopping projectile hops to: the nearest of this tick's characters, in the order they
   * joined, strictly inside its hop radius of where it stands - the first of equals - that is of
   * the other side, not untargetable, not in its list of those it has hit, with hit points and
   * accepting an attacker; none is tested for being alive.
   *
   * @param projectile the projectile that landed
   * @return the character, or null when none is in reach
   */
  public WorldEntity chainTarget(ProjectileEntity projectile) {
    int radius = projectile.getData().chainedHitRadius();
    int best = Integer.MAX_VALUE;
    WorldEntity chosen = null;
    for (WorldEntity entity : present) {
      if ((entity.side() & 1) == (projectile.side() & 1)
          || (entity.getView().getFlags() & entity.getView().getFlagBits().untargetable()) != 0
          || projectile.getHitIds().contains(entity.getId())
          || entity.getHitPoints() == null
          || !entity.getTargetView().acceptsAttacker(projectile.askerView(), false)) {
        continue;
      }
      int d =
          FixedMath.guardedDistance(
              projectile.getX() - entity.getView().getX(),
              projectile.getY() - entity.getView().getY());
      if (d < radius && d < best) {
        best = d;
        chosen = entity;
      }
    }
    return chosen;
  }

  /** Whether a projectile's buff may reach a character; see {@link #projectileAreaBuff}. */
  private static boolean projectileBuffReaches(ProjectileEntity projectile, WorldEntity target) {
    ProjectileData data = projectile.getData();
    boolean sameTeam = ((projectile.side() & 1) == 0) == ((target.side() & 1) == 0);
    if (!sameTeam && data.onlyOwnTroops()) {
      return false;
    }
    if (sameTeam && data.onlyEnemies()) {
      return false;
    }
    if (!HitPoints.alive(target.getHitPoints())) {
      return false;
    }
    if (target.untouchable()) {
      return false;
    }
    // The projectile's circle does not reach hidden units: a hidden one is passed by.
    if (target.passedBy(false)) {
      return false;
    }
    return target.getView().getState() != GridEntityState.WAITING_TO_DEPLOY;
  }

  /**
   * The characters a buff over a circle reaches: this tick's, in the order they joined, inside the
   * circle and passing the test, until the limit is used up; of the king-class towers only the
   * first takes a buff that deals damage. The apply of each asks the test again, as the walk
   * reaches it, and takes only a target that passes it a second time.
   */
  private List<WorldEntity> buffTargets(
      int x, int y, int radius, BuffData buff, int limit, Predicate<WorldEntity> reaches) {
    boolean damaging = BuffComponent.damagePerSecond(buff, 0) > 0;
    boolean towerTaken = false;
    List<WorldEntity> targets = new ArrayList<>();
    for (WorldEntity entity : present) {
      if (targets.size() >= limit) {
        break;
      }
      if (!ShapeTests.withinCircleShape(entity.getView(), x, y, radius) || !reaches.test(entity)) {
        continue;
      }
      if (entity.getTargetView().towerFlag()) {
        boolean skip = damaging && towerTaken;
        towerTaken |= damaging;
        if (skip) {
          continue;
        }
      }
      if (!reaches.test(entity)) {
        continue;
      }
      targets.add(entity);
    }
    return targets;
  }

  /** The name of the next buff instance listed in the battle. */
  String nextBuffKey() {
    return "buff_" + ++buffKeys;
  }

  /**
   * A buff-spawning action's buff on its owner: for its time, at its source's level and for its
   * source's side, with the source as the buff's. An owner riding on a parent takes it as any unit
   * does.
   *
   * @param owner the entity the buff goes on
   * @param action the action's name
   * @param buff the buff row's name
   * @param timeMs how long it lasts
   * @param source what applies it
   */
  void spawnBuff(WorldEntity owner, String action, String buff, int timeMs, SpawnHost source) {
    spawnBuff(owner, action, buff, timeMs, source, null);
  }

  /**
   * A buff-spawning action's buff on its owner, as {@link #spawnBuff(WorldEntity, String, String,
   * int, SpawnHost)} puts it, with a parent: the source, for a row that makes it the buff's
   * controller. A buff that stacks keeps it, and its leaving removes the instance.
   *
   * @param parent the buff's parent, or null for none
   */
  void spawnBuff(
      WorldEntity owner,
      String action,
      String buff,
      int timeMs,
      SpawnHost source,
      BattleEntity parent) {
    BuffData data = buffData(buff);
    events.buffSpawned(owner, action, data, timeMs, source.packedLevel(), source);
    owner
        .getBuffs()
        .applyByOwnAction(data, timeMs, source.packedLevel(), source, source.side(), parent);
  }

  /**
   * A Clone's creator: makes a clone of a unit on the unit's point, kept inside the arena, of the
   * unit's side and of its row, or of the row its row names as its cloned version, at the Clone's
   * level re-based on that row's rarity, named after the unit and how many clones of it came
   * before. The clone setter gives it 1 hit point of 1; it takes the deploy countdown the unit has
   * left and is set up as a clone through its setter, which with the combat gate after it leaves
   * its movement component on and its targeting component off, faces as the unit faces, and is
   * handed to the holder with its registration visit - in the clone state with no route, which
   * moves nothing - and joins the live list at the tick's closing cleanup, whose fold starts it as
   * any spawn: its own row's starting action is scheduled then. The unit's runs stay the unit's. It
   * takes a copy of every buff the unit carries, the Clone's own buff, just put on the unit, among
   * them, with the time each has left, and none of their start actions runs; then the copies of a
   * row that is not cloned are removed from it, their remove actions run on the clone. Then each
   * run the unit lists is told of the clone, and an air-to-ground run starts its row again on the
   * clone, the unit its cause, which copies the unit's run (see {@link WorldEntity#airToGround}).
   * Then the two move apart: the clone back toward its own side, and the unit, set up as a clone
   * too unless it dashes, forward. Last, for a unit that carries riders, each rider in their order
   * is cloned the same way, through this creator - its clone made on the rider's point, the rider
   * set up as a clone and moving apart too - and its clone attached to the unit's clone on the
   * rider's angle. The clone of a unit that carries riders never enters the deploying state, so it
   * makes no riders of its own. A unit still deploying is set up as a clone with its countdown
   * running, as its clone is: the countdown runs down in the setup, and each move apart that
   * reaches its duration holds its unit still until the countdown has ended, then resumes it.
   *
   * @param original the unit
   * @param instigator the Clone's area effect
   * @param action the Clone's action
   * @return the clone
   */
  CharacterEntity makeClone(CharacterEntity original, AreaEffectEntity instigator, Clone action) {
    UnitData row = original.getData();
    // The clone's row: the unit's own, or the row its row names as its cloned version.
    UnitData cloneRow = original.cloneRow();
    GridEntity at = original.getView();
    int fromX = at.getX();
    int fromY = at.getY();
    int made = cloneCounts.merge(original.name(), 1, Integer::sum) - 1;
    CharacterEntity clone =
        CharacterEntity.cloned(
            this,
            cloneRow,
            original.name() + "_clone" + made,
            original.side(),
            inset(fromX, tileMap.width()),
            inset(fromY, tileMap.height()),
            PackedLevel.level(PackedLevel.pack(instigator.packedLevel(), cloneRow.rarity())));
    clone.markClone(original);
    // The clone takes the deploy countdown the unit has left, before its setup, or after it for a
    // unit that carries riders.
    clone.enterCloneSetup(at.getDeployCountdown(), row.spawnAttach());
    clone.getView().setDirX(at.getDirX());
    clone.getView().setDirY(at.getDirY());
    List<Integer> visits = new ArrayList<>();
    for (int slot : new int[] {CharacterEntity.TARGETING_SLOT, CharacterEntity.MOVEMENT_SLOT}) {
      if (clone.isActive(slot)) {
        visits.add(slot);
      }
    }
    // Handed over as a spawn is: the fold that takes it in at the tick's closing cleanup starts
    // it, scheduling its row's starting action.
    clone.startOnAdmission();
    holder.addRegistered(clone);
    events.cloned(original, clone, instigator, visits);
    clone.getBuffs().copyFrom(original.getBuffs());
    // The creator tells each run the unit lists of the clone, in their order; only an air-to-ground
    // run acts on it: it schedules its row on the clone with the unit as the cause, whose start
    // then copies the unit's run.
    for (ActionInstance run : List.copyOf(original.actionHolder().running())) {
      if (run.getAction() instanceof AirToGround hold) {
        clone.actionHolder().schedule(hold, ActionHolder.OWN_DELAY, false, original.actionHolder());
      }
    }
    clone.startCloneMove(fromX, fromY, action);
    // A unit that dashes, or is in its ability's follow-up, keeps its state and does not move.
    int state = at.getState();
    if (state != GridEntityState.DASHING && state != GridEntityState.ABILITY_FOLLOW_UP) {
      original.enterCloneSetup();
      original.startCloneMove(fromX, fromY, action);
    }
    if (row.spawnAttach()) {
      List<CharacterEntity> riders = List.copyOf(original.riders());
      for (int i = 0; i < riders.size(); i++) {
        CharacterEntity rider = riders.get(i);
        CharacterEntity riderClone = makeClone(rider, instigator, action);
        riderClone.attachTo(clone, rider.getAttachAngle());
        events.riderAttached(clone, riderClone, i, rider.getAttachAngle());
      }
    }
    return clone;
  }

  /**
   * A clone's spawn is a clone: the clone setter on the child, the spawner as the unit it stands
   * for. Every spawner asks its source whether it is a clone, the one an action's spawn hands its
   * block to included, so the Skeletons a clone barrel's death action spawns are clones as its
   * death spawns are. The spawner calls it last, after the child's deploying state and deploy
   * countdown, and it sets only the clone mark and the hit points, so a child with a deploy time is
   * a clone made deploying: its countdown runs as any unit's, it takes no part in collision until
   * the countdown ends (the contact rule's deploying clone), and it resumes as any unit then.
   *
   * <p>A clone's area effect as the spawner makes clones too: the spawner asks it as it asks any
   * source, and hands the setter no original, as for every source that is not a character, so the
   * child takes its own shield rule. The death area of a cloned Suspicious Bush spawns its Bush
   * Goblins as clones of 1 hit point.
   */
  private static void cloneSpawn(SpawnHost spawner, CharacterEntity child) {
    if (spawner instanceof AreaEffectEntity area) {
      if (area.isClone()) {
        child.markClone(null);
      }
      return;
    }
    if (!(spawner instanceof CharacterEntity source) || !source.isClone()) {
      return;
    }
    child.markClone(source);
  }

  /**
   * A spawn source's clone answer: a clone, or a clone's area effect.
   *
   * @param source the source
   */
  private static boolean isClone(SpawnHost source) {
    return source instanceof CharacterEntity unit && unit.isClone()
        || source instanceof AreaEffectEntity area && area.isClone();
  }

  /** The battle tick of the entity tick in progress. */
  public int tick() {
    return tick;
  }

  /** What the world tells its observers. */
  public WorldEvents events() {
    return events;
  }

  /** The deflection of projectiles by the area effects that deflect them. */
  public ProjectileDeflection deflection() {
    return deflection;
  }

  /**
   * Queues the hit a deflection deals the deflector's parent: the projectile's own damage as a
   * travelling hit without a direction, the projectile its source, so the drain follows it with the
   * projectile's after-damage target buff as it does an impact's.
   */
  void queueDeflectedHit(ProjectileEntity projectile, WorldEntity parent, int damage, int hitId) {
    queuedHits.add(new TravellingHitDue(projectile, parent, damage, hitId, 0, 0, true));
  }

  /** The bit of FORCE_IS_GROUND in an entity's tag word, as the data numbers the game tags. */
  long forceIsGround() {
    return flagBits.forceIsGround();
  }

  /** The bit of FORCE_IS_AIR in an entity's tag word, as the data numbers the game tags. */
  long forceIsAir() {
    return flagBits.forceIsAir();
  }

  /**
   * Queues one hit of a buff's damage over time for the damage drain, as the game's damage entry
   * queues every hit it is handed, the buff pass's included: the carrier takes it, and dies of it,
   * at the drain of the tick, after the post-hooks (see {@link #queuedHits}). The source and the
   * side are the instance's as the hit is queued; no object leaves the battle between the buff pass
   * and the drain.
   *
   * @param target the entity carrying the buff
   * @param buff the instance whose damage it is
   * @param damage hit points it deals, after the carrier's own damage reduction
   */
  void dealBuffDamage(WorldEntity target, BuffInstance buff, int damage) {
    queuedHits.add(new BuffHitDue(target, buff, damage, buff.getSource(), buff.getSide()));
  }

  /**
   * Deals one hit of a buff's damage over time at the drain, tells the observers, and runs the
   * death it causes. The game hands the hit-points chain the buff's source as the instance held it
   * when the hit was queued, as it hands a direct hit its attacker: the source is what killed the
   * carrier, for the side the buff was applied for, so its killer's hook and the carrier's killed
   * action see the source (see {@link #entityDied}); a hit that lands and does not kill reaches the
   * source's reference drop as any other hit does (see {@link #referenceDrop}). A buff with no
   * source - one applied with none, or one whose source has left the battle, which it then forgets
   * as the game clears it - kills with nothing as what killed it, for its side, so the killed
   * action's cause is that side alone (see {@link #entityDied}).
   *
   * @param due the queued hit
   */
  private void dealBuffDamageNow(BuffHitDue due) {
    WorldEntity target = due.target();
    if (target.getHitPoints() == null) {
      return;
    }
    int before = target.getHitPoints().getHitPoints();
    // The damage of a buff comes from its source: an area effect, which is never struck back, or a
    // character, whose buff on a reflecting unit is not modelled.
    SpawnHost source = due.source();
    if (target.getData().reflectedAttackBuff() != null && source instanceof WorldEntity) {
      throw new UnsupportedOperationException(
          target.name() + " reflects and takes a buff's damage from a character, not modelled");
    }
    DamageResult result = target.takeDamageOverTime(due.damage(), source);
    reflect(target, (BattleEntity) source, before, result, 0, 0);
    events.buffDamaged(target, due.buff(), due.damage(), before, result);
    // Every object a buff's source can be is a battle entity; with no source, nothing.
    BattleEntity attacker = (BattleEntity) source;
    if (result.died()) {
      // The killing side is the one the buff was applied for.
      target.die(attacker, due.side());
    } else if (result.landed()) {
      referenceDrop(attacker, target, false);
    }
  }

  /**
   * Gives one heal of a buff's heal over time to its entity, capped by the buff's over-heal share,
   * and tells the observers.
   */
  void dealBuffHeal(WorldEntity target, BuffInstance buff, int heal) {
    int before = target.getHitPoints().getHitPoints();
    target.takeHeal(heal, buff.getBuff().allowedOverHealPercent());
    events.buffHealed(target, buff, heal, before);
  }

  /**
   * Deals one victim its share of an area effect's hit, tells the observers, and runs the death it
   * causes, with the area effect as what killed it. A victim that has left takes nothing.
   */
  DamageResult dealAreaEffectDamage(AreaEffectEntity areaEffect, WorldEntity victim, int damage) {
    if (victim == null || known.get(victim.getView()) != victim) {
      return DamageResult.NOTHING;
    }
    // The damage entry lets the hit of an area effect that reaches hidden units through while its
    // victim is hidden.
    int before = hitPointsOf(victim);
    DamageResult result =
        victim.takeDamage(damage, 0, 0, 0, areaEffect.getData().affectsHidden(), null, areaEffect);
    reflect(victim, areaEffect, before, result, 0, 0);
    events.areaEffectHit(areaEffect, victim, damage, result);
    if (result.died()) {
      victim.die(areaEffect);
    }
    return result;
  }

  /**
   * Pushes the victim of an area away from a point, as an area effect's or a projectile's impact
   * does: only a character can be pushed, and it decides whether it may be.
   *
   * @return true when it was pushed
   */
  public boolean pushByArea(WorldEntity victim, int fromX, int fromY, int distance) {
    return victim instanceof CharacterEntity character
        && character.pushedByArea(fromX, fromY, distance);
  }

  /** A position kept the creation's inset inside one axis of the arena. */
  private static int inset(int value, int cells) {
    return Math.min(
        Math.max(value, CardPlacement.CREATION_INSET),
        cells * TileMap.CELL_UNITS - CardPlacement.CREATION_INSET);
  }

  /**
   * The row of a child made other than by an action's spawn: its spawner's, its death's, its
   * projectile's, its rider's or its morph's. A row that limits the size of its spawn group is
   * refused, as the limit is not traced on these paths.
   */
  private UnitData spawnedRow(String name) {
    UnitData child = records.unit(name);
    if (child.groupMaxSize() > 0) {
      throw new UnsupportedOperationException(
          "spawning " + name + " asks for a limit on its group, which is not established");
    }
    return child;
  }

  /** Refuses the parts of a spawn whose behaviour is not established. */
  private static void refuseUnestablished(
      SpawnHost source, SpawnArguments arguments, UnitData data) {
    String refused = null;
    if (arguments.morph()) {
      refused = "a morph";
    } else if (arguments.enemy()) {
      refused = "a spawn for the other side";
    } else if (arguments.radius() != 0
        && source instanceof WorldEntity entity
        && entity.isCharacter()
        && entity.getData().deathSpawnMinRadius() != 0) {
      // Each child's radius would be drawn from the battle's random source.
      refused = "a ring around a character whose row draws each child's radius";
    } else if (arguments.radius() != 0
        && source instanceof WorldEntity entity
        && entity.isCharacter()
        && entity.getData().spawnAttach()
        && !arguments.deathSpawn()) {
      // Each child would be carried by the source, at its index and ring angle.
      refused = "a ring around a character whose row attaches its children";
    } else if (data.spawnPathfindSpeed() != 0) {
      refused = "a unit that paths to its spawn point";
    } else if (data.groupMaxSize() > 0
        && !(source instanceof CharacterEntity character
            && !character.getData().name().equals(data.name()))) {
      // A character spawning another row leaves the limit unused; any other limit is not traced.
      refused = "a limit on its group";
    }
    if (refused != null) {
      throw new UnsupportedOperationException(
          "spawning " + data.name() + " asks for " + refused + ", which is not established");
    }
  }

  @Override
  public void afterPostHooks() {
    // The queued hits land after every post-hook and before phase 3: the typed hits and, on a data
    // version that queues them, the direct hits, the shares of an area and the circle's and the
    // Kamikaze kills. The observers see them landed.
    drainTypedHits();
    // The observers share one copy of each list; with none, nothing is copied.
    if (events.listening()) {
      List<WorldEntity> snapshotOfPresent = present();
      List<ProjectileEntity> snapshotOfProjectiles = projectiles();
      events.afterPostHooks(snapshotOfPresent, snapshotOfProjectiles);
    }
  }

  /** Whether the object with an id is listed and, when it has hit points, alive. */
  private boolean listedAlive(int id) {
    BattleEntity entity = liveObject(id);
    if (entity == null) {
      return false;
    }
    if (entity instanceof WorldEntity world && world.getHitPoints() != null) {
      return world.getHitPoints().getHitPoints() > 0;
    }
    return true;
  }

  @Override
  public void postPass(int tick) {
    // The target locks grant the tick's requests after the phase-3 pending pass.
    if (locks != null) {
      locks.postPass();
    }
    grid.swap();
    index.clear();
    deflection.clear();
  }
}
