/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
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
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.BossBanditAbility;
import org.crforge.core.battle.action.CannonProjectileSpawn;
import org.crforge.core.battle.action.CardDeployListener;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.ConeShape;
import org.crforge.core.battle.action.CreateParallelProjectiles;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.OverrideAbilityButtonState;
import org.crforge.core.battle.action.RunActionOnTroopDestroyed;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.action.TakeDamage;
import org.crforge.core.battle.action.TargetIndicatorAttack;
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
import org.crforge.core.pathfinding.move.MovementGlobals;
import org.crforge.core.pathfinding.move.MovementState;
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
 * and about every arena entity that leaves.
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
            + " tests on what it finds by a unit that waits its turn, held by no run. Refused: a"
            + " death whose row sets a column of the slot not modelled, a least radius that"
            + " draws, a child without hit points that has a range, a spawner's turned or drawn"
            + " ring and a spawner child without hit points, a building, pathing or starting an"
            + " action, and a death hook with no attacker or no pending pass ahead of it. Left"
            + " out: the elixir a death gives. Not modelled: the game mode's own per-tick work"
            + " beside the index and the overlay, and the copy of the attacker the game makes as"
            + " a death hook's cause.")
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
   * route.
   */
  @Getter private final MovementGlobals movementGlobals;

  /** The neighbour answers the push pass and the avoidance handler ask for. */
  @Getter private final NeighbourQuery neighbourQuery;

  /** This tick's arena entities in ascending id. */
  private final List<WorldEntity> present = new ArrayList<>();

  /** This tick's views in the same order: what the index and the overlay are built from. */
  private final List<GridEntity> views = new ArrayList<>();

  /** This tick's projectiles in ascending id: the ones the tick visits. */
  private final List<ProjectileEntity> projectiles = new ArrayList<>();

  /**
   * This tick's deflecting area effects as the spatial index holds them, in its insertion order:
   * each with the buckets its square covered where it stood at the head of the tick. See {@link
   * IndexedDeflector}.
   */
  private final List<IndexedDeflector> indexedDeflectors = new ArrayList<>();

  /**
   * A deflecting area effect in the spatial index. The game's index holds every listed object whose
   * radius is at least 1, and an area effect answers its radius only when it deflects projectiles
   * (or has a collision behaviour, which no row carried here has), so a living Deflect is indexed
   * by its radius like a unit by its collision radius: in every bucket its square covers, without
   * the moving margin, as an area effect has no components. Java's index holds the arena entities'
   * views only; the area effect's buckets are kept here instead, and a query that would visit them
   * tests it on its live position and radius as the index would.
   *
   * @param deflector the area effect
   * @param xLow its first bucket along the width
   * @param xHigh its last bucket along the width
   * @param yLow its first bucket along the length
   * @param yHigh its last bucket along the length
   */
  private record IndexedDeflector(
      AreaEffectEntity deflector, int xLow, int xHigh, int yLow, int yHigh) {}

  /**
   * Every arena entity admitted so far and not yet gone, keyed by its view. An entity joins at its
   * first pre-pass and leaves at the cleanup that removes it.
   */
  private final Map<GridEntity, WorldEntity> known = new IdentityHashMap<>();

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

  /**
   * Starts the battle's random source from a state of its own, before anything draws.
   *
   * @param seed the state the first draw steps
   */
  public void seed(int seed) {
    random = new BattleRandom(seed);
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
  private final List<WorldObserver> observers = new ArrayList<>();

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
   * then followed by the attacker's buff on damage when the hit landed.
   *
   * @param attacker the entity whose hit it is
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  private record DirectHitDue(
      WorldEntity attacker, TargetView target, int damage, int directionX, int directionY)
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
   * The kill of a fallen king's circle waiting for the drain, where it is dealt.
   *
   * @param target the entity the circle reached
   * @param radius the circle's radius
   */
  private record CircleKillDue(WorldEntity target, int radius) implements QueuedHit {}

  /**
   * A Kamikaze unit's kill of itself waiting for the drain, where it is dealt.
   *
   * @param unit the unit whose hit ended
   */
  private record KamikazeKillDue(WorldEntity unit) implements QueuedHit {}

  /**
   * A kill action's kill of its owner waiting for the drain, where it is dealt.
   *
   * @param target the kill action's owner
   * @param killer the entity that caused the action, or null for none
   */
  private record ActionKillDue(WorldEntity target, WorldEntity killer) implements QueuedHit {}

  /**
   * A tiebreaker's clearing's kill of a character waiting for the drain, where it is dealt.
   *
   * @param target the character the clearing reached
   */
  private record ClearingKillDue(CharacterEntity target) implements QueuedHit {}

  /**
   * A hit the damage drain deals: a typed hit, a damage-taking action's hit, or a direct hit, a
   * share of a character's or a projectile's area, a projectile's hit on its one target, a hit of a
   * buff's damage over time, a circle's kill, a Kamikaze unit's kill, a kill action's kill or a
   * tiebreaker's clearing's kill.
   */
  private sealed interface QueuedHit
      permits TypedHit,
          DirectHitDue,
          AreaHitDue,
          ProjectileAreaHitDue,
          ProjectileHitDue,
          TravellingHitDue,
          ActionDamageDue,
          BuffHitDue,
          CircleKillDue,
          KamikazeKillDue,
          ActionKillDue,
          ClearingKillDue {}

  /**
   * A travelling hit of a projectile flying to a point waiting for the drain: dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it at once,
   * from the direction of the pass's centre, with nothing after it. A deflection's hit on the
   * deflector's parent goes to the same entry, without a direction.
   *
   * @param projectile the projectile whose body covered the entity
   * @param target the entity it covered
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param hitId the id the pass carries
   * @param directionX the entity's position less the pass's centre, along the width
   * @param directionY the same along the length
   */
  private record TravellingHitDue(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY)
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
   * pending pass kills still pushes the units around it in that tick's movement pass.
   *
   * <p>A buff's damage over time goes the same way (see {@link #dealBuffDamage(WorldEntity,
   * BuffInstance, int)}): the buff pass queues each hit it finds due, and the carrier takes it, and
   * dies of it, at the drain. So a carrier the damage kills is still alive for the rest of the buff
   * pass and for the post-hooks, and what a later carrier's buff makes in that pass - a spawner
   * buff's child - is made before what the dying carrier's death leaves.
   */
  private final List<QueuedHit> queuedHits = new ArrayList<>();

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

  /** The published globals' flags the battle has read, each on its first use. */
  private final Map<String, Boolean> globalFlags = new HashMap<>();

  /**
   * A published global's flag, read from the records on its first use.
   *
   * @param name the global's name
   */
  boolean globalFlag(String name) {
    return globalFlags.computeIfAbsent(name, records::globalBoolean);
  }

  /**
   * The global that decides when a travelling hit queued for the drain dooms what it hit for the
   * rest of its volley: set, by the damage queued for the entity against its hit points and shield;
   * clear, by the one hit's damage against its hit points, which is refused (see {@link
   * #queuedHits}); set in 16.402.18.
   */
  private static final String PROJECTILE_DAMAGE_BUG = "V16_PROJECTILE_DAMAGE_BUG";

  /**
   * The global for how near its owner an enemy deflecting area effect may be for a spell-like
   * projectile deflected before to pass it untouched.
   */
  private static final String DOUBLE_DEFLECT_SPELL_MIN_DISTANCE =
      "DOUBLEDEFLECT_SPELL_MIN_DISTANCE";

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
   */
  public void load(GameTables tables) {
    requireModelled(tables);
    declare(tables);
    this.flagBits = EntityFlags.of(tables);
    this.records = new BattleRecords(tables);
    this.actions = new ActionRows(tables, records);
  }

  /**
   * Refuses tables whose data version is not one the battle models: a data version game client
   * 16.402.17 has run ({@link GameVersions#CLIENT_16_402_17_DATA}).
   *
   * @param tables the game tables
   * @throws UnsupportedOperationException for any other data version
   */
  public static void requireModelled(GameTables tables) {
    if (!GameVersions.CLIENT_16_402_17_DATA.contains(tables.version())) {
      throw new UnsupportedOperationException(
          "the battle models the rules of game client "
              + GameVersions.CLIENT_16_402_17
              + ", which runs data versions "
              + new TreeSet<>(GameVersions.CLIENT_16_402_17_DATA)
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
    observers.add(observer);
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
    queuedHits.add(new DirectHitDue(attacker, target, damage, directionX, directionY));
  }

  /**
   * Deals the damage of a carried push's hit to an entity, as {@link #dealDamage(WorldEntity,
   * TargetView, int, int, int)} deals a direct hit's, but past the hidden test.
   *
   * @param attacker the entity carrying the push
   * @param target the entity hit
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealCarriedPushDamage(
      WorldEntity attacker, WorldEntity target, int damage, int directionX, int directionY) {
    return dealDamage(attacker, target.getTargetView(), damage, directionX, directionY, true);
  }

  private DamageResult dealDamage(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean passesHidden) {
    WorldEntity entity = known.get(target.getEntity());
    if (entity == null) {
      return DamageResult.NOTHING;
    }
    int before = hitPointsOf(entity);
    DamageResult result =
        entity.takeDamage(damage, 0, directionX, directionY, passesHidden, attacker, attacker);
    reflect(entity, attacker, before, result, directionX, directionY);
    for (WorldObserver observer : observers) {
      observer.damageDealt(tick, entity, damage, result);
    }
    if (result.died()) {
      entity.die(attacker);
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
   * for its side, on every reflected hit; and once per attack - the source's attack count and id,
   * listed on the unit and never cleared - the reflected damage at the unit's level, its crown
   * tower column against a crown tower, through the damage entry with the unit as the attacker, the
   * battle's holds and the hidden test lifted. A death it causes runs at once, before the unit's
   * own.
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
      reflected(none);
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
    if (struck.getHitPoints() == null || !target.reflectOnce(source)) {
      reflected(
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
    int before = struck.getHitPoints().getHitPoints();
    DamageResult hit = struck.takeReflectedDamage(target, damage, directionX, directionY);
    for (WorldObserver observer : observers) {
      observer.reflectedHit(tick, target, struck, damage, hit);
    }
    reflected(
        new Reflection(
            target,
            attacker,
            source,
            struck,
            hitSpeed,
            row.reflectedAttackBuff(),
            row.reflectedAttackBuffDurationMs(),
            level,
            damage,
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
   * Refuses a hook on a target someone else holds a lock on: the battle's locks, made by a
   * collector's first step, are asked about the owner and the target, and a lock held by another
   * would end the hook as an ordinary arrival, which no reference holds.
   *
   * @param projectile the hooking projectile
   */
  public void refuseLockedHook(ProjectileEntity projectile) {
    WorldEntity target = projectile.getTarget();
    if (locks != null
        && target != null
        && locks.heldByOther(projectile.getOwner().getId(), target.getId(), 0)) {
      throw new UnsupportedOperationException(
          projectile.name() + " hooks " + target.name() + ", which another holds, not modelled");
    }
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
    for (WorldObserver observer : observers) {
      observer.dragStateSet(tick, projectile, unit, old, state);
    }
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
    int state = target.getView().getState();
    if (state == GridEntityState.JUMPING
        || (state == GridEntityState.DASHING && target.getData().jumpHeight() >= 1)) {
      target.getView().setZ(0);
      hookRequest(projectile, target, GridEntityState.STANDING);
      character.resetRoute();
    }
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

  /** Tells every observer of a special load a unit's targeting visit armed. */
  void specialArmed(
      CharacterEntity unit,
      WorldEntity reference,
      long distanceSquared,
      int ringMin,
      int ringMax,
      int loadMs,
      int afterMs) {
    for (WorldObserver observer : observers) {
      observer.specialArmed(
          tick, unit, reference, distanceSquared, ringMin, ringMax, loadMs, afterMs);
    }
  }

  /** Tells every observer that a unit's targeting forgot the projectile it was held on. */
  void holdLeft(WorldEntity unit, ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.holdLeft(tick, unit, projectile);
    }
  }

  /** Tells every observer that a unit forgot the hooking projectile it followed. */
  void followLeft(WorldEntity unit, ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.followLeft(tick, unit, projectile);
    }
  }

  private void reflected(Reflection reflection) {
    for (WorldObserver observer : observers) {
      observer.reflected(tick, reflection);
    }
  }

  /**
   * Hands a launched projectile to the holder, which gives it its id at once and admits it at the
   * next cleanup, tells every observer of the launch, and runs its registration pass.
   */
  public void launch(ProjectileEntity projectile) {
    holder.add(projectile);
    for (WorldObserver observer : observers) {
      observer.projectileLaunched(tick, projectile);
    }
    registrationPass(projectile);
  }

  /**
   * The pass a projectile runs as the holder registers it, after its launcher's running actions
   * have heard of it: one that flies to a point, unless it sweeps out and back, hits what its body
   * covers where it starts at once, its body widened by the row's start radius, whatever delay it
   * waits before it flies.
   */
  private void registrationPass(ProjectileEntity projectile) {
    // Its launcher's running actions hear of it first: an enchanting buff may hand it a copy.
    if (projectile.getOwner() != null) {
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
    queuedHits.add(new ActionKillDue(target, killer));
  }

  /** A kill action's kill, dealt at the damage drain. */
  private void killNow(WorldEntity target, WorldEntity killer) {
    int before = target.getHitPoints() == null ? 0 : target.getHitPoints().getHitPoints();
    DamageResult result = target.takeKill(null, killer);
    for (WorldObserver observer : observers) {
      observer.damageDealt(tick, target, before, result);
    }
    if (result.died()) {
      target.die(killer);
    }
  }

  /**
   * The area object a unit makes as it enters the deploying state through its setter: at its point,
   * for its side and at its level, handed to the holder, and updated at once, so it hits on the
   * tick it is made.
   *
   * @param unit the unit
   */
  void spawnAreaObject(CharacterEntity unit) {
    AreaEffectEntity area =
        createAreaEffect(
            unit.getData().spawnAreaObject(),
            unit.getView().getX(),
            unit.getView().getY(),
            unit.side(),
            unit.getPackedLevel(),
            null,
            "spawn_area_object",
            unit.name());
    area.updateAtOnce();
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
    for (WorldObserver observer : observers) {
      observer.deployPushed(tick, unit, radius, distance, found, pushed);
    }
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
   * then set to the unit's deploying state, whose entry makes its area object. The damage on its
   * way to the unit passes to the new object. The unit leaves at the closing cleanup.
   *
   * <p>A morph hands the new object to the removal notice of every entity that referenced the old
   * one, whose replacement path clears a continuous-damage attacker's ramp; that path is not
   * established, so a morph of a unit such an attacker references is refused.
   *
   * <p>Like any object the holder admits, the new object is started at the closing cleanup's fold:
   * its row's starting action - the evolved Goblin Drill's relocation, which takes the place of its
   * area object - is scheduled then, and runs in its first pending pass of the next tick.
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
    for (WorldObserver observer : observers) {
      observer.morphed(tick, old, made);
    }
  }

  /** Tells every observer a card play made the unit, before the play starts it. */
  void characterPlayed(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.characterPlayed(tick, unit);
    }
  }

  /** Tells every observer a unit's ability was requested, and whether it is cast at once. */
  void abilityRequested(CharacterEntity unit, boolean now) {
    for (WorldObserver observer : observers) {
      observer.abilityRequested(tick, unit, now);
    }
  }

  /** Tells every observer a unit's ability fired. */
  void abilityFired(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.abilityFired(tick, unit);
    }
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
    queuedHits.add(new KamikazeKillDue(unit));
  }

  /** The Kamikaze kill, dealt at the damage drain. */
  private void kamikazeKillNow(WorldEntity unit) {
    int before = unit.getHitPoints().getHitPoints();
    DamageResult result = unit.takeKill(unit, unit);
    for (WorldObserver observer : observers) {
      observer.kamikazeKilled(tick, unit, before, result);
    }
    if (result.died()) {
      unit.die(unit);
    }
  }

  /**
   * Tells every observer a Kamikaze unit's hit ended, before the kill it may run.
   *
   * @param unit the unit
   * @param kills true when the end kills it
   */
  void kamikazeHitEnded(WorldEntity unit, boolean kills) {
    for (WorldObserver observer : observers) {
      observer.kamikazeHitEnded(tick, unit, kills);
    }
  }

  /**
   * Deals one step of a Kamikaze unit's drain over its time, with itself as the attacker on its own
   * side: refused where damage is forbidden, passing the battle's holds, so that a step that takes
   * the last hit point runs its death as its own killer. Every observer is told of the step.
   *
   * @param unit the unit
   * @param damage the step
   */
  void kamikazeDrain(WorldEntity unit, int damage) {
    int before = unit.getHitPoints().getHitPoints();
    DamageResult result = unit.takeKamikazeDrain(damage);
    for (WorldObserver observer : observers) {
      observer.kamikazeDrained(tick, unit, damage, before, result);
    }
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
    queuedHits.add(new CircleKillDue(target, radius));
  }

  /** The circle's kill, dealt at the damage drain. */
  private void circleKillNow(WorldEntity target, int radius) {
    DamageResult result = target.takeKill();
    for (WorldObserver observer : observers) {
      observer.circleKilled(tick, target, radius);
    }
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Kills a character of either side that a tiebreaker's clearing reached: it is resumed at once,
   * and its kill, its whole hit points with no attacker, is queued for the damage drain, as a
   * fallen king's circle's is. The character stays alive through the passes of the update the
   * clearing runs and dies at that update's drain (see {@link #queuedHits}), so whatever reads it
   * before - a tower aiming at it, its own movement, the place its death spawns are made at - finds
   * it as it was.
   *
   * @param target the character
   */
  public void clearingKill(CharacterEntity target) {
    target.resume();
    queuedHits.add(new ClearingKillDue(target));
  }

  /**
   * The clearing's kill, dealt at the damage drain: the whole hit points with no attacker, told to
   * every observer as the clearing's kill rather than a hit.
   */
  private void clearingKillNow(CharacterEntity target) {
    DamageResult result = target.takeKill();
    for (WorldObserver observer : observers) {
      observer.clearingKilled(tick, target);
    }
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
    for (WorldObserver observer : observers) {
      observer.drained(tick, target, damage, target.getHitPoints().getHitPoints(), result.died());
    }
    if (result.died()) {
      target.die(null);
    }
  }

  /** Tells every observer a unit asked to push itself back after a launch. */
  void pushbackRequested(
      WorldEntity unit, boolean started, int fromX, int fromY, MovementState pushback) {
    for (WorldObserver observer : observers) {
      observer.pushbackRequested(tick, unit, started, fromX, fromY, pushback);
    }
  }

  /** Tells every observer a unit's pushback visit moved it off a cell it may not stand on. */
  void relocated(WorldEntity unit, int x, int y, int toX, int toY) {
    for (WorldObserver observer : observers) {
      observer.relocated(tick, unit, x, y, toX, toY);
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
    for (WorldObserver observer : observers) {
      observer.areaHit(tick, attacker, victim, damage, hitId, result);
    }
    if (result.died()) {
      victim.die(attacker);
    } else if (result.landed()) {
      referenceDrop(attacker, victim, false);
    }
    return result;
  }

  /** Tells every observer what the area of an entity's hit did, once its victims are dealt. */
  void areaDamaged(WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    for (WorldObserver observer : observers) {
      observer.areaDamaged(tick, owner, area, outcome);
    }
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
    for (WorldObserver observer : observers) {
      observer.projectileImpacted(tick, projectile, target, damage, result);
    }
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
   * Every tower of a side still in the battle, in the order the holder lists them: the side's list
   * of map objects. A destroyed one is listed until the cleanup that removes it.
   */
  List<TowerEntity> towers(int side) {
    List<TowerEntity> out = new ArrayList<>();
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof TowerEntity tower && tower.side() == side) {
        out.add(tower);
      }
    }
    return out;
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
   * Tells every observer an elixir collector paid its king.
   *
   * @param collector the collector
   * @param amount the whole elixir it paid
   */
  void elixirCollected(WorldEntity collector, int amount) {
    for (WorldObserver observer : observers) {
      observer.elixirCollected(tick, collector, collector.side(), amount);
    }
  }

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
   */
  public TowerEntity kingTower(int side) {
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof TowerEntity tower && tower.getData().king() && tower.side() == side) {
        return tower;
      }
    }
    return null;
  }

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

  /** Tells every observer that a hiding building's deploy end ran its targeting visit. */
  void deployEndVisited(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.deployEndVisited(tick, unit);
    }
  }

  /** Tells every observer of one visit of a hiding building's hide handler. */
  void hideVisited(
      CharacterEntity unit, int state, int before, int after, int step, List<String> effects) {
    for (WorldObserver observer : observers) {
      observer.hideVisited(tick, unit, state, before, after, step, List.copyOf(effects));
    }
  }

  /** Tells every observer that a hiding building scheduled one of its hide handler's actions. */
  void hidingHookScheduled(CharacterEntity unit, String column, String action) {
    for (WorldObserver observer : observers) {
      observer.hidingHookScheduled(tick, unit, column, action);
    }
  }

  /** Tells every observer of one step of a king tower's activation. */
  void activation(TowerEntity king, ActivationEvent event) {
    for (WorldObserver observer : observers) {
      observer.activation(tick, king, event);
    }
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
    for (WorldEntity entity : present) {
      entity.registerCandidates(present);
    }
    index.rebuild(views);
    indexDeflectors(snapshot);
    FootprintOverlay.buildOverlay(grid, views);
    grid.setChangeFlags(grid.getChanged().clone());
    // The target locks' step, before the entities' pre-hooks: a lock whose target or holder is no
    // longer listed alive goes, then the queued releases.
    if (locks != null) {
      locks.prePass(this::listedAlive);
    }
    List<WorldEntity> snapshotOfPresent = present();
    for (WorldObserver observer : observers) {
      observer.afterPrePass(tick, snapshotOfPresent);
    }
  }

  /**
   * The side lists' part of a removal: a removed entity leaves every arena entity's default targets
   * in the same cleanup, after each entity's own notice has run.
   */
  @Override
  public void entityRemoved(BattleEntity removed) {
    if (removed instanceof AreaEffectEntity areaEffect) {
      for (WorldObserver observer : observers) {
        observer.areaEffectRemoved(tick, areaEffect);
      }
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
    for (WorldObserver observer : observers) {
      observer.entityRemoved(tick, gone);
    }
    // A child leaves its source's group as it is released, after every notice of the removal,
    // and a unit its card's group chain.
    if (gone instanceof CharacterEntity child) {
      CharacterEntity source = child.leaveGroup();
      if (source != null) {
        for (WorldObserver observer : observers) {
          observer.groupUnlinked(tick, source, child);
        }
      }
      if (child.leaveChain()) {
        chainUnlinked(child);
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
   * @param variant true for a variant card's play, which a listener of its side refuses
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
        if (variant && (owner.side() & 1) == (side & 1)) {
          throw new UnsupportedOperationException(
              played
                  + " played as "
                  + deployed
                  + " while "
                  + owner.name()
                  + "'s "
                  + instance.getAction().name()
                  + " listens for its side's card plays: a variant card's play, whose kind the"
                  + " group's lists answer, is not modelled");
        }
        String scheduled =
            run.hear(
                owner.side(),
                side,
                deployed,
                played,
                () -> records.matchCard(deployed).cost(),
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
        for (WorldObserver observer : observers) {
          observer.cardPlayHeard(
              tick,
              owner,
              instance.getAction().name(),
              side,
              played,
              deployed,
              run.getTotal(),
              scheduled);
        }
      }
    }
  }

  /** Tells the observers a unit was linked into its card's group chain. */
  void chainLinked(CharacterEntity unit, CharacterEntity after) {
    for (WorldObserver observer : observers) {
      observer.chainLinked(tick, unit, after);
    }
  }

  /** Tells the observers a unit left its card's group chain. */
  void chainUnlinked(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.chainUnlinked(tick, unit);
    }
  }

  /** Tells the observers a run of Goblinstein's ability started on an area effect. */
  void goblinsteinStarted(AreaEffectEntity owner, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinStarted(tick, owner, action, phase);
    }
  }

  /** Tells the observers a run of Goblinstein's ability connected, to nothing for null. */
  void goblinsteinConnected(AreaEffectEntity owner, BattleEntity connected) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinConnected(tick, owner, connected);
    }
  }

  /**
   * Makes the death area of a run of Goblinstein's ability at a point: for the area effect's side
   * and at its level, re-based on the death area's own rarity, the area effect its parent and
   * following nothing. It is handed to the holder inside the cleanup that removed the connected
   * unit, which admits it as it ends.
   *
   * @param owner the area effect the run is on
   * @param row the death area's row
   * @param x the point along the width
   * @param y the point along the length
   * @return the death area
   */
  AreaEffectEntity goblinsteinDeathArea(AreaEffectEntity owner, String row, int x, int y) {
    return createAreaEffect(
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
  }

  /** Tells the observers a run of Goblinstein's ability made its death area. */
  void goblinsteinDeathAreaMade(
      AreaEffectEntity owner, WorldEntity left, AreaEffectEntity deathArea, int x, int y) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinDeathAreaMade(tick, owner, left, deathArea, x, y);
    }
  }

  /** Tells the observers a run of Goblinstein's ability ended its death area. */
  void goblinsteinDeathAreaEnded(AreaEffectEntity owner, AreaEffectEntity deathArea) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinDeathAreaEnded(tick, owner, deathArea);
    }
  }

  /** Tells the observers a run of Goblinstein's ability saw a cast, its end, or ended a tether. */
  void goblinsteinStepped(AreaEffectEntity owner, String step) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinStepped(tick, owner, step);
    }
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
    for (WorldObserver observer : observers) {
      observer.tetherActivated(tick, owner, target, row);
    }
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

  /** Tells the observers a tether's damage pass ran its query. */
  void tetherDamagePass(
      AreaEffectEntity owner, int ax, int ay, int bx, int by, List<WorldEntity> found) {
    for (WorldObserver observer : observers) {
      observer.tetherDamagePass(tick, owner, ax, ay, bx, by, found);
    }
  }

  /**
   * A tether's hit: an area effect's hit, the area effect the attacker, with the damage entry's
   * hidden test lifted and the hit's direction carried to a death.
   *
   * @param owner the area effect the tether runs on
   * @param victim the object hit
   * @param damage the damage
   * @param directionX the hit's direction along the width
   * @param directionY the hit's direction along the length
   */
  void tetherHit(
      AreaEffectEntity owner, WorldEntity victim, int damage, int directionX, int directionY) {
    int before = hitPointsOf(victim);
    DamageResult result = victim.takeDamage(damage, 0, directionX, directionY, true, null, owner);
    reflect(victim, owner, before, result, directionX, directionY);
    for (WorldObserver observer : observers) {
      observer.tetherHit(tick, owner, victim, damage, directionX, directionY, result);
    }
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
    for (WorldObserver observer : observers) {
      observer.tetherHitAction(tick, owner, target, row);
    }
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
    for (WorldObserver observer : observers) {
      observer.championRefunded(tick, slot, mana, before, kingElixir.elixir(slot.side()));
    }
  }

  /**
   * What a champion slot reads of its side's characters of a champion row, in live-list order, for
   * the observers: before its step.
   */
  List<ChampionView> championViews(int side) {
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

  /** Tells the observers a champion slot stepped in its king's run pass. */
  void championStepped(ChampionController slot, int elixir, List<ChampionView> views) {
    for (WorldObserver observer : observers) {
      observer.championStepped(tick, slot, elixir, views);
    }
  }

  /** The raw elixir of a side's king, in ten-thousandths. */
  int elixir(int side) {
    return kingElixir.elixir(side);
  }

  /** Tells the observers the deck pass gave a champion slot its champion. */
  void championDeckPass(ChampionController slot) {
    for (WorldObserver observer : observers) {
      observer.championDeckPass(tick, slot);
    }
  }

  /** Tells the observers a champion slot followed a card play. */
  void championFollowed(ChampionController slot, String play) {
    for (WorldObserver observer : observers) {
      observer.championFollowed(tick, slot, play);
    }
  }

  /** Tells the observers a champion slot heard a paid ability and requested its live copies'. */
  void championActivated(ChampionController slot, List<CharacterEntity> requested) {
    for (WorldObserver observer : observers) {
      observer.championActivated(tick, slot, requested);
    }
  }

  /** Tells the observers a champion slot's cooldown ran out. */
  void championCooldownOut(ChampionController slot) {
    for (WorldObserver observer : observers) {
      observer.championCooldownOut(tick, slot);
    }
  }

  /** Tells the observers a unit whose dashes chain started a dash, with its chain's bookkeeping. */
  void chainDashStarted(
      CharacterEntity unit, int fromX, int fromY, int aimX, int aimY, int radius) {
    for (WorldObserver observer : observers) {
      observer.chainDashStarted(tick, unit, fromX, fromY, aimX, aimY, radius);
    }
  }

  /** Tells the observers a chain found its next target as the unit left its dash. */
  void chainDashed(CharacterEntity unit, WorldEntity next, int count, int x, int y) {
    for (WorldObserver observer : observers) {
      observer.chainDashed(tick, unit, next, count, x, y);
    }
  }

  /** Tells the observers a chain ended as the unit left its dash. */
  void chainDashEnded(CharacterEntity unit, int count, TargetView reference) {
    for (WorldObserver observer : observers) {
      observer.chainDashEnded(tick, unit, count, reference);
    }
  }

  /** Tells the observers an ability gave its unit its buff. */
  void abilityBuffed(CharacterEntity unit, String buff, int timeMs, int packedLevel) {
    for (WorldObserver observer : observers) {
      observer.abilityBuffed(tick, unit, buff, timeMs, packedLevel);
    }
  }

  /** Tells the observers a child was linked into its source's group. */
  void groupLinked(CharacterEntity source, CharacterEntity child) {
    for (WorldObserver observer : observers) {
      observer.groupLinked(tick, source, child);
    }
  }

  /**
   * A death: runs when a hit takes an arena entity's hit points to zero, in the pass that lands it,
   * once the entity has switched off what it no longer does.
   *
   * <p>The death slot comes first, inside the killing hit. Its death damage lands around the entity
   * - see {@link #deathDamage} - and then its death spawn stands on the ring around it or on its
   * point - see {@link #deathSpawn}. A death whose row sets a column of the slot the battle does
   * not model is refused: a second or third death spawn, a death projectile or area effect, a
   * starting buff taken back, a spawned area object ended, and the parts of the death spawn's
   * placement not established. The elixir a death gives is left out, as the battle models no
   * elixir.
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
   * <p>Refused rather than guessed: a death hook with no cause, which the game gives a cause that
   * carries only a side, and one scheduled after the tick's last pending pass, which would leave
   * with the entity. The cause is the attacker's own holder, where the game hands the hook a copy
   * of the attacker made at the kill; a hook reads no more of it than its presence and its row,
   * which the copy shares.
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
    for (WorldObserver observer : observers) {
      observer.killedDoneScheduled(tick, killer, dying, action, inPendingPass);
    }
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
   * @param attacker what landed the hit: an arena entity, a projectile, or anything else
   * @param hit the entity it hit
   * @param kills whether the hit killed it
   */
  private void referenceDrop(BattleEntity attacker, WorldEntity hit, boolean kills) {
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
    for (WorldObserver observer : observers) {
      observer.referenceDroppedOnHit(tick, unit, hit, via, kills, active, dropped);
    }
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
    for (WorldObserver observer : observers) {
      observer.deathElixirPaid(tick, dying, killingSide, amount);
    }
  }

  /**
   * The death handler's hook: the dying unit's killed action alone, with what killed it as the
   * cause; the death slot schedules the death action (see {@link #slotDeathAction}). None when the
   * row has no killed action, when the death has no killing side, or when the unit killed itself on
   * the killing side. Refused rather than guessed: a killed action with no attacker, whose cause
   * would carry a side alone, and one scheduled after the tick's last pending pass.
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
    ActionHolder cause;
    int side;
    if (attacker instanceof WorldEntity entity) {
      cause = entity.actionHolder();
      side = entity.side();
    } else if (attacker instanceof ProjectileEntity projectile) {
      cause = projectile.actionHolder();
      side = projectile.getSide();
    } else if (attacker instanceof AreaEffectEntity areaEffect) {
      cause = areaEffect.actionHolder();
      side = areaEffect.side();
    } else {
      throw new UnsupportedOperationException(
          dying.name()
              + " died with no attacker; the cause its killed action would carry, a side alone, is"
              + " not modelled");
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
    for (WorldObserver observer : observers) {
      observer.deathHooksScheduled(tick, dying, attacker, side, hooks, inPendingPass);
    }
    dying
        .actionHolder()
        .schedule(
            actions.build(data.onKilledAction(), binding(dying)),
            ActionHolder.OWN_DELAY,
            false,
            cause);
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
    for (WorldObserver observer : observers) {
      observer.deathHooksScheduled(tick, dying, dying, dying.side(), hooks, inPendingPass);
    }
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
    for (WorldObserver observer : observers) {
      observer.diedAtRemoval(tick, dying);
    }
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
    for (WorldObserver observer : observers) {
      observer.decayDied(tick, dying, hitPointsBefore);
    }
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
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, spawner, spawned, x, y);
      }
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
    for (WorldObserver observer : observers) {
      observer.characterSpawned(tick, spawner, spawned, x, y);
    }
  }

  void destroyedAtLimit(CharacterEntity spawner) {
    for (WorldObserver observer : observers) {
      observer.destroyedAtLimit(tick, spawner);
    }
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
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, parent, rider, x, y);
      }
      rider.attachTo(parent, angle);
      for (WorldObserver observer : observers) {
        observer.riderAttached(tick, parent, rider, i, angle);
      }
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
    for (WorldObserver observer : observers) {
      observer.parentLeft(tick, rider, parent);
    }
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

  /** Tells the observers the combat gate dropped an entity's reference. */
  void combatGateDropped(WorldEntity entity, TargetView reference, int hitSpeed) {
    for (WorldObserver observer : observers) {
      observer.combatGateDropped(tick, entity, reference, hitSpeed);
    }
  }

  /** Tells the observers the combat gate switched a stunned entity's targeting off or on. */
  void combatComponentSwitched(WorldEntity entity, boolean on, int hitSpeed) {
    for (WorldObserver observer : observers) {
      observer.combatComponentSwitched(tick, entity, on, hitSpeed);
    }
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
   */
  public void castSpell(
      DeployCard card, int cardLevel, int side, int x, int y, String name, int play) {
    AreaEffectEntity areaEffect = null;
    if (card.areaEffect() != null) {
      areaEffect = createAreaEffect(card.areaEffect(), x, y, side, cardLevel, null, "cast", name);
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
      firstProjectile = castProjectiles(card, data, king, cardLevel, side, x, y, play);
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
      int play) {
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
    for (WorldObserver observer : observers) {
      observer.projectileAreaEffect(tick, projectile, areaEffect);
    }
  }

  /**
   * The character spawn of a projectile's impact: its children in the card formation around the
   * impact point, the spread their collision radius when there are several, each created kept
   * inside the arena, at the projectile's level, deploying for the row's deploy time when it has
   * one, and registered with its registration visit; only then is a child on the ground moved off
   * the water, onto the point the relocation gives where the child then stands, so the step its
   * registration visit took is kept. Each child joins the live list at the next cleanup's fold,
   * which starts it: its row's starting action is scheduled then. A deflected projectile with a
   * deflected spawn is refused.
   *
   * @param projectile the projectile that landed
   * @param x the impact point along the width
   * @param y the impact point along the length
   */
  public void impactSpawn(ProjectileEntity projectile, int x, int y) {
    ProjectileData data = projectile.getData();
    if (projectile.getDeflections() >= 1 && data.deflectedCharacterSpawn() != null) {
      // A deflected projectile's impact makes its deflected spawn in place of its spawned
      // character, as many of them; that spawn is held by no reference.
      throw new UnsupportedOperationException(
          data.name()
              + " is deflected and its impact makes "
              + data.deflectedCharacterSpawn()
              + " in place of "
              + data.spawnCharacter()
              + ", which is not modelled");
    }
    UnitData child = spawnedRow(data.spawnCharacter());
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
    int lane = LaneAssignment.lane(w, h, w, x, y, -1, 0, tileMap::bits);
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
      int childLane = LaneAssignment.lane(w, h, w, cx, cy, x, 0, tileMap::bits);
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
      // The child is queued by the add that registers it now: it joins the live list at the next
      // cleanup's fold, which starts it, scheduling its row's starting action then. It carries the
      // projectile's play, so a champion slot that follows the play follows it.
      spawned.setDeployIndex(projectile.getDeployIndex());
      spawned.startOnAdmission();
      holder.addRegistered(spawned);
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, projectile, spawned, cx, cy);
      }
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
   * row's start radius, before the next is made.
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
      registrationPass(projectile);
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
   * would run the deflection pass instead, and every deflection {@link #deflect} does not model.
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
      if (!deflectors().isEmpty()) {
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
      for (AreaEffectEntity deflector : listedDeflectors(x, y, radius, halfHeight)) {
        // The deflect handler answers no for a projectile that has arrived or is on the area
        // effect's own team, and does nothing else then. One it turns around hits nothing here.
        if (deflect(deflector, projectile)) {
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
   * Puts this tick's deflecting area effects into the index's buckets, in the snapshot's order, as
   * the index rebuild inserts every listed object whose radius is at least 1: the buckets of its
   * square where it stands at the head of the tick. See {@link IndexedDeflector}.
   */
  private void indexDeflectors(List<BattleEntity> snapshot) {
    indexedDeflectors.clear();
    for (BattleEntity entity : snapshot) {
      if (entity instanceof AreaEffectEntity areaEffect
          && areaEffect.getData().deflectsProjectiles()) {
        int radius = areaEffect.deflectRadius();
        if (radius < 1) {
          continue;
        }
        int ax = areaEffect.getX();
        int ay = areaEffect.getY();
        indexedDeflectors.add(
            new IndexedDeflector(
                areaEffect,
                (ax - radius) >> SpatialIndex.BUCKET_SHIFT,
                (ax + radius) >> SpatialIndex.BUCKET_SHIFT,
                (ay - radius) >> SpatialIndex.BUCKET_SHIFT,
                (ay + radius) >> SpatialIndex.BUCKET_SHIFT));
      }
    }
  }

  /**
   * The deflecting area effects a building-aware index query lists, in the index's insertion order:
   * one in a bucket the query visits whose circle, at its live position and radius, meets the
   * query's box - with a half height - or its circle. An area effect is no building.
   */
  private List<AreaEffectEntity> listedDeflectors(int x, int y, int radius, int halfHeight) {
    List<AreaEffectEntity> out = new ArrayList<>();
    int xLow = (x - radius) >> SpatialIndex.BUCKET_SHIFT;
    int xHigh = (x + radius) >> SpatialIndex.BUCKET_SHIFT;
    int yLow = (y - radius) >> SpatialIndex.BUCKET_SHIFT;
    int yHigh = (y + radius) >> SpatialIndex.BUCKET_SHIFT;
    if (xLow > xHigh || yLow > yHigh) {
      return out;
    }
    for (IndexedDeflector indexed : indexedDeflectors) {
      int fromX = Math.max(Math.max(xLow, indexed.xLow()), 0);
      int toX = Math.min(Math.min(xHigh, indexed.xHigh()), index.getWidth() - 1);
      int fromY = Math.max(Math.max(yLow, indexed.yLow()), 0);
      int toY = Math.min(Math.min(yHigh, indexed.yHigh()), index.getHigh() - 1);
      if (fromX > toX || fromY > toY) {
        continue;
      }
      AreaEffectEntity deflector = indexed.deflector();
      int ex = deflector.getX();
      int ey = deflector.getY();
      int er = deflector.deflectRadius();
      boolean inside =
          halfHeight != 0
              ? ShapeTests.withinBox(
                  ex, ey, er, x - radius, y - halfHeight, 2 * radius, 2 * halfHeight)
              : ShapeTests.withinCircle(ex, ey, er, x, y, radius);
      if (inside) {
        out.add(deflector);
      }
    }
    return out;
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
   * The deflection pass of a flying projectile, at a point and height: after each move, at its new
   * position, and at its arrival, at its aim. A projectile that never deflects passes untouched.
   * Every other is measured as a point, standing on the ground, in three dimensions, against every
   * area effect of the live list, in its order, that deflects projectiles and whose life has not
   * run out: within the area effect's deflection radius widened by the projectile's own deflect
   * radius, or its body radius without one. The first that touches it and deflects it ends the
   * pass; one that touches it but answers no - it has arrived, or is on the area effect's team -
   * lets the pass go on. A projectile that no deflection turns around passes untouched.
   *
   * <p>Refused rather than guessed, once a deflecting area effect is listed: a projectile measured
   * on the ground plane, by a box, or only at its target's point (whose arrival is measured at a
   * stored point, not at the aim); and every deflection {@link #deflect} does not model.
   *
   * @param projectile the projectile
   * @param x the point along the width
   * @param y the point along the length
   * @param z the height
   * @return true when a deflecting area effect turned the projectile around
   */
  public boolean deflectPass(ProjectileEntity projectile, int x, int y, int z) {
    List<AreaEffectEntity> deflectors = deflectors();
    if (deflectors.isEmpty()) {
      return false;
    }
    ProjectileData data = projectile.getData();
    if ((data.deflectBehaviour() & ProjectileData.NO_DEFLECT) != 0) {
      return false;
    }
    // The shape's radii: the projectile's deflect radius, or its body's without one.
    int rx = data.deflectRadius() >= 1 ? data.deflectRadius() : data.projectileRadius();
    int ry = data.deflectRadius() >= 1 ? 0 : data.projectileRadiusY();
    if ((data.deflectBehaviour()
                & (ProjectileData.IGNORE_HEIGHT | ProjectileData.CHECK_ONLY_TARGET_POSITION))
            != 0
        || ry > 0) {
      throw new UnsupportedOperationException(
          projectile.name()
              + " ("
              + data.name()
              + ") flies by a deflecting area effect, whose deflection of it is not modelled");
    }
    for (AreaEffectEntity deflector : deflectors) {
      if (deflector.getCountdown() < 1) {
        continue;
      }
      int reach = deflector.deflectRadius() + rx;
      int squared =
          FixedMath.guardedSumOfSquares(
              FixedMath.s32((long) x - deflector.getX()),
              FixedMath.s32((long) y - deflector.getY()),
              z);
      if (squared < reach * reach && deflect(deflector, projectile)) {
        return true;
      }
    }
    return false;
  }

  /** The area effects of the live list that deflect projectiles, in its order. */
  private List<AreaEffectEntity> deflectors() {
    List<AreaEffectEntity> out = new ArrayList<>();
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof AreaEffectEntity areaEffect
          && areaEffect.getData().deflectsProjectiles()) {
        out.add(areaEffect);
      }
    }
    return out;
  }

  /**
   * Refuses a projectile whose deflection is not modelled, once an area effect would deflect it:
   * one with a deflection behaviour, a deflect radius or an action on its deflector, a body; one
   * that hops, flies to a point, homes for a time, waits a random delay, hooks or stops at
   * collisions; one that spawns a projectile, belongs to a chain or a volley's group. A spell such
   * as the Fireball, whose deflection behaviour sends it at the enemy nearest the area effect
   * without a target, is among them.
   *
   * <p>A pingpong projectile on its sweep is modelled, its round body and its flight to a point
   * with it, as the Executioner's axe is: the pass over its body's cells turns it around on its
   * way, and, deflected, it flies on as any projectile that flies to a point, which a deflecting
   * area effect of the other side may turn around again. The runs on it, as the evolved axe's
   * controller, are left as they are: the deflection asks them only for the damage of its hit on
   * the deflector's parent. Refused: one not deflected before that is deflected at its return.
   *
   * <p>A spell-like projectile is modelled too: one whose only deflection behaviour is the spells'
   * tower share, with a deflect radius of its own and no body, as the Fireball, the Rocket, the
   * Snowball and the evolved Cannon's bombs are (see {@link #deflect}).
   */
  private static void refuseDeflection(AreaEffectEntity deflector, ProjectileEntity projectile) {
    ProjectileData data = projectile.getData();
    boolean pingpong = data.pingpongVisualTimeMs() >= 1;
    boolean spellLike = spellLike(data);
    if (pingpong
        && projectile.getDeflections() == 0
        && projectile.getPingpongTimeMs() >= data.pingpongVisualTimeMs()) {
      throw new UnsupportedOperationException(
          projectile.name()
              + " ("
              + data.name()
              + ") is a pingpong projectile deflected at its return, by "
              + deflector.name()
              + ", not modelled");
    }
    if ((data.deflectBehaviour() != 0 && !spellLike)
        || (data.deflectRadius() != 0 && !spellLike)
        || data.actionOnDeflector() != null
        || (data.projectileRadius() != 0 && !pingpong)
        || data.projectileRadiusY() != 0
        || data.chainedHitRadius() >= 1
        || (data.homingLike() && !pingpong)
        || data.homingTimeMs() >= 1
        || data.randomDelayMs() >= 1
        || data.dragBackSpeed() >= 1
        || data.checkCollisions()
        || data.spawnProjectile() != null
        || projectile.getChain() != null
        || projectile.isGrouped()) {
      throw new UnsupportedOperationException(
          projectile.name()
              + " ("
              + data.name()
              + ") flies within "
              + deflector.name()
              + " ("
              + deflector.getData().name()
              + "), whose deflection of it is not modelled");
    }
  }

  /**
   * The deflection of a projectile by an area effect that touches it: none for a projectile that
   * has arrived or is on the area effect's own team. Otherwise the object the area effect follows,
   * its parent, takes the projectile's damage at its level - not the deflected share - with a fresh
   * hit id, when it has hit points, is not listed among those the projectile has hit and the shared
   * validator lets the projectile reach it: the listening runs of a projectile with an action
   * holder change the damage first, as for a travelling hit (the evolved Executioner's controller
   * hands its own amount, by the parent's distance from the axe's start as it is then), and the hit
   * is queued for the damage drain, as a travelling hit is, without a direction. Then the
   * projectile is sent back at its root owner, for the parent's side, so the drain deals the hit,
   * and the runs hear of it, after the turn-around.
   *
   * <p>A spell-like projectile (see {@link #spellLike}) goes another way. Once deflected, one whose
   * owner, a character, stands within DOUBLEDEFLECT_SPELL_MIN_DISTANCE of the area effect is not
   * deflected at all: it passes on untouched. One without a target - a cast spell - is sent at a
   * point instead of its root owner: the position of the crown tower of the other team than the
   * area effect's nearest the area effect (see {@link #nearestEnemyCrownTower}), with no target;
   * with no such tower it is sent back at its root owner after all. A bomb a barrage drops onto its
   * area effect has that area effect as its root and its target while the area effect is in the
   * battle: it is sent back at it, at its point, which it keeps as what it was dropped onto. The
   * parent takes the projectile's damage either way, before the redirect.
   *
   * <p>Refused rather than guessed: the projectiles {@link #refuseDeflection} names, an area effect
   * that follows nothing, a projectile without a root owner - whose deflection finishes it - or one
   * a king tower fired that is not spell-like, which searches for the nearest enemy instead when it
   * has no target, and one carrying copies that change its damage; a spell-like projectile the
   * search would send at a king tower, which no recording holds; a projectile an area effect
   * launched that was not dropped onto it. A deflection past the most a projectile takes
   * (MAX_DEFLECTION_TIMES) turns it around all the same and then ends its flight.
   *
   * @return true when the projectile was deflected
   */
  private boolean deflect(AreaEffectEntity deflector, ProjectileEntity projectile) {
    if (projectile.isReleased() || (deflector.side() & 1) == (projectile.side() & 1)) {
      return false;
    }
    refuseDeflection(deflector, projectile);
    boolean spellLike = spellLike(projectile.getData());
    WorldEntity source = projectile.getRoot();
    // A bomb dropped onto its area effect has that area effect as its root.
    AreaEffectEntity areaSource =
        source == null && spellLike && projectile.getAreaLauncher() != null
            ? projectile.getAreaLauncher()
            : null;
    if (areaSource != null && projectile.getAreaTarget() != areaSource) {
      throw new UnsupportedOperationException(
          deflector.name()
              + " deflects "
              + projectile.name()
              + ", launched by an area effect it was not dropped onto, which is not modelled");
    }
    if (spellLike && (source != null || areaSource != null) && nearOwner(deflector, projectile)) {
      return false;
    }
    if (!(deflector.getFollow() instanceof WorldEntity parent)) {
      throw new UnsupportedOperationException(
          deflector.name() + " deflects without an object it follows, which is not modelled");
    }
    if ((source == null && areaSource == null) || (!spellLike && source.getData().king())) {
      throw new UnsupportedOperationException(
          deflector.name()
              + " deflects "
              + projectile.name()
              + " without a root owner or from a king tower, which is not modelled");
    }
    if (projectile.carriesListeners()) {
      throw new UnsupportedOperationException(
          projectile.name() + " is deflected carrying copies that change its damage, not modelled");
    }
    // A spell-like projectile without a target is sent at the enemy crown tower nearest the area
    // effect; one with a target, as a bomb on its area effect, goes back at its root.
    WorldEntity tower = null;
    if (spellLike && projectile.getTarget() == null && projectile.getAreaTarget() == null) {
      tower = nearestEnemyCrownTower(deflector, projectile);
      if (tower != null && tower.getData().king()) {
        throw new UnsupportedOperationException(
            deflector.name()
                + " sends "
                + projectile.name()
                + " at the king tower "
                + tower.name()
                + ", which is not modelled");
      }
    }
    // The parent takes the projectile's own damage, through its damage reduction, unless the
    // projectile has hit it already: a pingpong projectile on its way back after hitting it.
    if (parent.getHitPoints() != null
        && !projectile.getHitIds().contains(parent.getId())
        && ReferenceValidator.sharedValidate(
            projectile.areaOwner(),
            parent.getTargetView(),
            false,
            false,
            false,
            true,
            validatorQueries)) {
      int hitId = nextHitId();
      int damage = projectile.undeflectedDamage();
      if (projectile.hasActionHolder()) {
        damage =
            projectile.listenedDamage(damage, hitId, parent.getTargetView().crownTower(), parent);
      }
      queuedHits.add(new TravellingHitDue(projectile, parent, damage, hitId, 0, 0));
    }
    WorldEntity sentAt = null;
    if (tower != null) {
      projectile.deflectAt(parent, tower.getView().getX(), tower.getView().getY());
    } else if (source == null) {
      projectile.deflectAt(parent, areaSource.getX(), areaSource.getY());
    } else {
      projectile.deflect(parent, source);
      sentAt = source;
    }
    // A deflection past the most a projectile takes still turns it around, and then ends its
    // flight, as a release does.
    if (projectile.getDeflections() > globalNumber("MAX_DEFLECTION_TIMES")) {
      projectile.finishOverDeflected();
    }
    for (WorldObserver observer : observers) {
      observer.projectileDeflected(tick, deflector, projectile, parent, sentAt);
    }
    return true;
  }

  /**
   * Whether a projectile is spell-like as a deflection reads it: its only deflection behaviour is
   * the spells' tower share, and it has no body, so it is measured by its deflect radius.
   */
  private static boolean spellLike(ProjectileData data) {
    return data.deflectBehaviour() == ProjectileData.USE_SPELLS_TOWER_DAMAGE_MUL
        && data.projectileRadius() == 0
        && data.projectileRadiusY() == 0;
  }

  /**
   * The gate on a spell-like projectile deflected before: its owner - the parent of the area effect
   * that turned it - is a character standing within DOUBLEDEFLECT_SPELL_MIN_DISTANCE of this area
   * effect, by the guarded sum of squares against the distance squared.
   */
  private boolean nearOwner(AreaEffectEntity deflector, ProjectileEntity projectile) {
    WorldEntity owner = projectile.getOwner();
    if (projectile.getDeflections() < 1
        || owner == null
        || owner.kind() != BattleEntity.KIND_CHARACTER) {
      return false;
    }
    int least = globalNumber(DOUBLE_DEFLECT_SPELL_MIN_DISTANCE);
    int squared =
        FixedMath.guardedSumOfSquares(
            owner.getView().getX() - deflector.getX(), owner.getView().getY() - deflector.getY());
    return squared < least * least;
  }

  /**
   * The crown tower a deflected spell-like projectile is sent at: of the live list, in its order,
   * every princess-like tower (a row that is a summoner tower) and king tower (a summoner) of the
   * other team than the area effect's that the projectile has not hit and that the shared validator
   * lets it reach, its own team not asked; the nearest to the area effect by the guarded sum of
   * squares, the first on a tie. None when there is none.
   */
  private WorldEntity nearestEnemyCrownTower(
      AreaEffectEntity deflector, ProjectileEntity projectile) {
    WorldEntity best = null;
    int bestSquared = FixedMath.INT_MAX;
    for (BattleEntity entity : holder.entities()) {
      if (!(entity instanceof WorldEntity candidate)
          || !(candidate.getData().king() || candidate.getData().summonerTower())
          || (candidate.side() & 1) == (deflector.side() & 1)
          || projectile.getHitIds().contains(candidate.getId())
          || !ReferenceValidator.sharedValidate(
              projectile.areaOwner(),
              candidate.getTargetView(),
              true,
              false,
              false,
              true,
              validatorQueries)) {
        continue;
      }
      int squared =
          FixedMath.guardedSumOfSquares(
              deflector.getX() - candidate.getView().getX(),
              deflector.getY() - candidate.getView().getY());
      if (squared < bestSquared) {
        best = candidate;
        bestSquared = squared;
      }
    }
    return best;
  }

  /**
   * Whether the damage queued for the drain will kill an entity, as a travelling hit just queued
   * asks it, with the global V16_PROJECTILE_DAMAGE_BUG set: the hits queued for the entity and not
   * yet dealt, the one just queued among them, add up to at least its hit points and shield. The
   * hits counted are those of the queueing damage entry, which adds each one's damage to the
   * entity's queued total and the drain empties: travelling hits, direct hits, a projectile's hit
   * on its one target, the shares of a character's or a projectile's area and the hits of a buff's
   * damage over time. A damage-taking action's hit is queued by an entry of its own, which adds
   * nothing to the total: its amount is worked out only at the drain, so it never counts here.
   *
   * <p>Refused rather than guessed: the global clear, where the one hit's damage alone is held
   * against the entity's hit points, which no data version holds.
   *
   * @param entity the entity hit
   */
  private boolean queuedKill(WorldEntity entity) {
    if (!globalFlag(PROJECTILE_DAMAGE_BUG)) {
      throw new UnsupportedOperationException(
          "a travelling hit is queued with "
              + PROJECTILE_DAMAGE_BUG
              + " clear, whose test of a kill is not modelled");
    }
    HitPoints hitPoints = entity.getHitPoints();
    int queued = 0;
    for (QueuedHit hit : queuedHits) {
      if (hit instanceof TravellingHitDue travelling && travelling.target() == entity) {
        queued += travelling.damage();
      } else if (hit instanceof DirectHitDue direct
          && known.get(direct.target().getEntity()) == entity) {
        queued += direct.damage();
      } else if (hit instanceof AreaHitDue area && area.victim() == entity) {
        queued += area.damage();
      } else if (hit instanceof ProjectileAreaHitDue share && share.victim() == entity) {
        queued += share.damage();
      } else if (hit instanceof BuffHitDue buffHit && buffHit.target() == entity) {
        queued += buffHit.damage();
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
   * order: none on its own side when it hits enemies only, none on an entity it has hit already; an
   * untouchable character is listed as hit and spared; a character on a layer the projectile does
   * not reach, in the air, or, for a projectile that does not reach the air, in a jump or in a dash
   * under a row with a jump height, is spared without being listed. An entity with hit points takes
   * the projectile's damage at its level, or its crown-tower share, as the listening runs of a
   * projectile with an action holder change it - the evolved Executioner's controller in place of
   * it - from the direction of the pass's centre, and is listed as hit, a carrier whose row
   * attaches its riders with the ids of its riders (held by the Bowler's over a Goblin Giant, whose
   * riders' Spear Goblins it then passes over); then a character whose movement is still on is
   * pushed the row's pushback away from the projectile, the row's push-all lifting the gates. A
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
    if (entity.untouchable()) {
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
    int damage = crownTower ? projectile.towerDamage() : projectile.damage();
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
        new TravellingHitDue(projectile, entity, damage, hitId, view.getX() - x, view.getY() - y));
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

  /** Tells the observers a hit met an entity's shield. */
  void shieldHit(WorldEntity target, int damage, int before, int after) {
    for (WorldObserver observer : observers) {
      observer.shieldHit(tick, target, damage, before, after);
    }
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
   * Tells the observers a listed buff that gives a charge range reset a character's charge.
   *
   * @param unit the character
   * @param instance the instance just listed
   * @param before the charge progress before the reset
   * @param after the charge progress after it
   */
  void buffChargeReset(CharacterEntity unit, BuffInstance instance, int before, int after) {
    for (WorldObserver observer : observers) {
      observer.buffChargeReset(tick, unit, instance, before, after);
    }
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
    for (WorldObserver observer : observers) {
      observer.shieldLostScheduled(tick, unit, action, cause, inPendingPass);
    }
  }

  /** Tells the observers a character's spawner fired. */
  /**
   * Tells every observer that a unit started a dash.
   *
   * @param unit the dashing unit
   * @param reference what it dashed at
   * @param fromX where it stood, along the width
   * @param fromY where it stood, along the length
   * @param aimX the point it dashed toward, along the width
   * @param aimY the point it dashed toward, along the length
   */
  void dashStarted(
      CharacterEntity unit, TargetView reference, int fromX, int fromY, int aimX, int aimY) {
    for (WorldObserver observer : observers) {
      observer.dashStarted(tick, unit, reference, fromX, fromY, aimX, aimY);
    }
  }

  /**
   * Tells every observer that a unit landed its dash.
   *
   * @param unit the unit
   * @param hit what its landing hit: the target of a single hit, or null for an area or nothing
   * @param damage the landing's damage, or 0 when nothing was hit
   * @param area true when the landing hit an area
   */
  void dashLanded(CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
    for (WorldObserver observer : observers) {
      observer.dashLanded(tick, unit, hit, damage, area);
    }
  }

  /** Tells every observer that a unit's charge completed. */
  void chargeCompleted(CharacterEntity unit, int progress) {
    for (WorldObserver observer : observers) {
      observer.chargeCompleted(tick, unit, progress);
    }
  }

  /** Tells every observer that a unit fully charged at its last movement visit is no longer. */
  void chargeLost(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.chargeLost(tick, unit);
    }
  }

  /** Tells every observer that a unit's movement pass had its state changed. */
  void movementStateRequested(CharacterEntity unit, int from, int to) {
    for (WorldObserver observer : observers) {
      observer.movementStateRequested(tick, unit, from, to);
    }
  }

  void spawnerFired(
      CharacterEntity spawner, String row, int count, int radius, int timerAfter, int waveMade) {
    for (WorldObserver observer : observers) {
      observer.spawnerFired(tick, spawner, row, count, radius, timerAfter, waveMade);
    }
  }

  /**
   * The death slot: what a dying object's row does as it dies, in order - its starting buff taken
   * off; its ManaOnDeath paid to its own side's king; its area effect at its point, for its side
   * and at its level; what its buffs leave; what the object switches off; its death damage; its
   * death spawn; its death projectiles. The switches come after the buffs: a child a buff leaves is
   * visited as it is made, and finds the dying object still moving. A death whose row sets a column
   * of the slot the battle does not model is refused, and so is the death of one whose area object
   * is still in the battle, which would end it.
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
    if (data.spawnAreaObject() != null) {
      for (BattleEntity entity : holder.entities()) {
        if (entity instanceof AreaEffectEntity area
            && area.getData().name().equals(data.spawnAreaObject())
            && (area.side() & 1) == (dying.side() & 1)) {
          throw new UnsupportedOperationException(
              dying.name() + " died with its area object in the battle, whose end is not modelled");
        }
      }
    }
    if (data.deathAreaEffect() != null) {
      createAreaEffect(
          data.deathAreaEffect(),
          dying.getView().getX(),
          dying.getView().getY(),
          dying.side(),
          dying.getPackedLevel(),
          null,
          "death",
          dying.name());
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
    for (WorldObserver observer : observers) {
      observer.deathManaPaid(tick, dying, side, amount);
    }
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
    for (WorldObserver observer : observers) {
      observer.destroyedNoticed(tick, dying, destroyedListeners.size());
    }
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
    for (WorldObserver observer : observers) {
      observer.destroyedListening(tick, owner, action, true);
    }
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
    for (WorldObserver observer : observers) {
      observer.destroyedListening(tick, owner, action, false);
    }
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

  /** Tells the observers a unit counted a soul for a death. */
  void soulCounted(CharacterEntity unit, WorldEntity dying, int souls) {
    for (WorldObserver observer : observers) {
      observer.soulCounted(tick, unit, dying, souls);
    }
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
   * <p>Refused rather than guessed: a least radius, which draws each radius from the battle's
   * random source; a row with a spawner limit, whose count is what the limit has left; and a clone,
   * whose projectiles carry its clone answer to what their impacts make.
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
    } else if (dying instanceof CharacterEntity character && character.isClone()) {
      refused = "is a clone, whose projectiles carry it";
    }
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
      launch(projectile);
      for (WorldObserver observer : observers) {
        observer.deathProjectileLaunched(tick, dying, projectile);
      }
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
        for (WorldObserver observer : observers) {
          observer.characterSpawned(tick, dying, spawned, x, y);
        }
        made.add(spawned);
      }
      for (WorldObserver observer : observers) {
        observer.buffDeathSpawn(tick, dying, instance, made);
      }
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
    pushingArea(dying, radius, damage, towerDamage, 0, push);
  }

  /**
   * The landing hit of a dash with a radius: the area damage around the landing point, the dashing
   * unit as its owner, its damage the row's dash damage at the unit's level, taken whole by a crown
   * tower too, under the hit id the landing took, with no split and a thousand victims at most. It
   * hits air units when the row attacks air and ground ones unless the row attacks air alone, and
   * pushes each victim still alive, with a movement component and a row that does not ignore
   * pushback, the row's dash pushback away from the landing point.
   *
   * @param dasher the unit that landed
   * @param damage the dash damage at its level
   * @param hitId the id the landing took
   */
  void dashLandingArea(CharacterEntity dasher, int damage, int hitId) {
    UnitData data = dasher.getData();
    pushingArea(dasher, data.dashRadius(), damage, damage, hitId, data.dashPushBack());
  }

  /**
   * An area damage around an entity, the entity as its owner and its targeting as the validator's,
   * that pushes each victim away from its centre: no split, a thousand victims at most, air units
   * when the entity's row attacks air and ground ones unless it attacks air alone.
   */
  private void pushingArea(
      WorldEntity owner, int radius, int damage, int towerDamage, int hitId, int push) {
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
                return entityOf(victim.getEntity()).passedBy(false);
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
    areaDamaged(owner, area, outcome);
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
          int lane =
              LaneAssignment.lane(
                  tileMap.width(),
                  tileMap.height(),
                  tileMap.width(),
                  fromX,
                  fromY,
                  -1,
                  0,
                  tileMap::bits);
          for (WorldObserver observer : observers) {
            observer.ringLaneAsked(tick, dying, fromX, fromY, lane);
          }
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
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, dying, spawned, madeX, madeY);
      }
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
   * that caused the action, or the area effect whose hit ran it; anything else that caused it is no
   * source, and the level scaling of a damage from such a cause is refused.
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
    if (cause != null && source == null && areaSource == null && damage.levelScaled()) {
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
    }
    queuedHits.add(
        new ActionDamageDue(source, areaSource, target, damage, added, directionX, directionY));
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
    for (WorldObserver observer : observers) {
      observer.areaBuff(tick, areaEffect, buff, time, List.of(target));
    }
    AreaEffectEntity parent = buff.controlledByParent() ? areaEffect : null;
    target
        .getBuffs()
        .apply(buff, time, areaEffect.getPackedLevel(), areaEffect, areaEffect.side(), parent);
  }

  /**
   * Deals every queued hit, in the order they were queued. A typed hit: the type's pipeline, a
   * damage id from the battle's hit counter when the type takes one, the typed hit's entry, then
   * the type's action on the source and its action on the target, and the observers are told. A
   * direct hit, a share of a character's or a projectile's area, or a projectile's hit on its one
   * target: the damage dealt, with its reflect, its observers, its death or the reference drop; a
   * hit of a buff's damage over time; a circle's, a Kamikaze unit's, a kill action's or a
   * tiebreaker's clearing's kill.
   */
  private void drainTypedHits() {
    List<QueuedHit> due = new ArrayList<>(queuedHits);
    queuedHits.clear();
    for (QueuedHit queued : due) {
      if (queued instanceof DirectHitDue direct) {
        DamageResult result =
            dealDamage(
                direct.attacker(),
                direct.target(),
                direct.damage(),
                direct.directionX(),
                direct.directionY());
        drainBuffOnDamage(direct.attacker(), direct.target(), result);
        continue;
      }
      if (queued instanceof AreaHitDue area) {
        dealAreaDamageNow(area.attacker(), area.victim(), area.damage(), area.hitId());
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
        dealProjectileDamage(
            hit.projectile(),
            hit.target(),
            hit.damage(),
            hit.hitId(),
            hit.directionX(),
            hit.directionY());
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
      if (queued instanceof CircleKillDue kill) {
        circleKillNow(kill.target(), kill.radius());
        continue;
      }
      if (queued instanceof KamikazeKillDue kamikaze) {
        kamikazeKillNow(kamikaze.unit());
        continue;
      }
      if (queued instanceof ActionKillDue kill) {
        killNow(kill.target(), kill.killer());
        continue;
      }
      if (queued instanceof ClearingKillDue kill) {
        clearingKillNow(kill.target());
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
      if (result.accepted() && source != null && source.getData().buffOnDamage() != null) {
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
      for (WorldObserver observer : observers) {
        observer.typedHitDealt(tick, source, target, amount, damageId, result);
      }
    }
  }

  /**
   * Deals a damage-taking action's hit: nothing to a target without hit points; nothing at all from
   * a target that takes no damage; otherwise the damage's amount for the target - its tower amount
   * against a crown tower when it gives one - plus the added amount, scaled by the source's level
   * unless NoScaling, floored at 0 behind the source's percentages (which no buff changes) unless
   * NoAmplification - an area effect has none - then lowered by the target's protection and floored
   * at 0 unless NoProtection. The damage entry deals it with the source counting it (an area effect
   * counts nothing), the target's runs told whether it is a Reflected hit, the hidden test lifted
   * under DamagesHidden, and the death it causes runs at once. A source that has left the battle is
   * no source; the level scaling of a hit from one is refused. A source whose row sets a buff on
   * damage, which the drain applies after a hit it lets through, is refused.
   *
   * <p>The level scaling is the card damage scaling of the source's own row and level: a
   * character's, a building's or a tower's, or an area effect's.
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
    TakeDamage.Damage damage = due.damage();
    if (damage.levelScaled()
        && source == null
        && areaSource == null
        && (due.source() != null || due.areaSource() != null)) {
      throw new UnsupportedOperationException(
          "a damage-taking action's hit scaled by the level of a source no longer in the battle"
              + " is not modelled");
    }
    int amount = 0;
    if ((target.getView().getFlags() & target.getView().getFlagBits().noDamage()) == 0) {
      amount = damage.amount(target.getTargetView().isCrownTowerTarget()) + due.added();
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
      }
      if (source != null && !damage.noAmplification()) {
        refuseTypedPercents(source, "a damage-taking action's hit");
        amount = Math.max(amount, 0);
      }
      if (!damage.noProtection()) {
        amount = Math.max(target.getBuffs().damageReduction(amount), 0);
      }
    }
    DamageResult result =
        target.takeActionDamage(
            source,
            areaSource != null ? areaSource : source,
            amount,
            damage.reflected(),
            damage.damagesHidden(),
            due.directionX(),
            due.directionY());
    if (result.died()) {
      target.die(areaSource != null ? areaSource : source);
    }
    if (result.accepted() && source != null && source.getData().buffOnDamage() != null) {
      throw new UnsupportedOperationException(
          source.name() + " deals a damage-taking action's hit with a BuffOnDamage, not modelled");
    }
    for (WorldObserver observer : observers) {
      observer.typedHitDealt(tick, source, target, amount, 0, result);
    }
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
    for (WorldObserver observer : observers) {
      observer.typedHitDealt(tick, null, target, amount, damageId, result);
    }
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
    for (WorldObserver observer : observers) {
      observer.typedHitDealt(tick, null, target, amount, 0, result);
    }
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
    for (WorldObserver observer : observers) {
      observer.guardRegistered(tick, guard);
    }
    guard.faceToward(x, y);
    guard.deployAfterRegistration();
    for (WorldObserver observer : observers) {
      observer.guardStarted(tick, areaEffect, action.name(), phase, guard, x, behind, gx, gy);
    }
  }

  /** A guard-spawning run's first step on an area effect, which finishes it. */
  void guardFirstStepped(AreaEffectEntity areaEffect, SpawnGuard action) {
    for (WorldObserver observer : observers) {
      observer.guardFirstStepped(tick, areaEffect, action.name());
    }
  }

  /** A step of a guard's run. */
  void guardStepped(
      CharacterEntity guard, boolean charging, long tags, boolean done, List<String> calls) {
    for (WorldObserver observer : observers) {
      observer.guardStepped(tick, guard, charging, tags, done, calls);
    }
  }

  /** A Boss Bandit ability's run started on a unit. */
  void bossBanditAbilityStarted(
      CharacterEntity unit,
      BossBanditAbility action,
      int phase,
      int warpTick,
      int lockTick,
      List<Boolean> requests) {
    for (WorldObserver observer : observers) {
      observer.bossBanditAbilityStarted(
          tick, unit, action.name(), phase, warpTick, lockTick, requests);
    }
  }

  /** A step of a Boss Bandit ability's run that changed it or asked for its lock again. */
  void bossBanditAbilityStepped(
      CharacterEntity unit, boolean locked, int releaseMs, List<String> calls) {
    for (WorldObserver observer : observers) {
      observer.bossBanditAbilityStepped(tick, unit, locked, releaseMs, calls);
    }
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
   * whose row keeps its target through it is refused: no reference holds it.
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
    for (WorldObserver observer : observers) {
      observer.warped(
          tick,
          unit,
          action.name(),
          phase,
          startX,
          startY,
          referenceBefore == null ? null : referenceBefore.name());
    }
  }

  /**
   * A warp's pending damage reset, the instant warp's and the flying warp's start alike: the unit's
   * reset hook, the same as entering a pathfinding state. The damage on its way to the unit is set
   * to 0, then every projectile aimed at it whose row allows it loses it as its target, handing
   * nothing back; each flies on to its aim, where the unit last stood for it, and lands on nothing.
   * A projectile aimed at the unit whose row keeps its target through the reset, which would hand
   * its damage back from the emptied amount on its arrival, is refused: no reference holds it.
   *
   * @param unit the unit
   * @param action the warp row's name
   */
  void resetPendingDamageAtWarp(CharacterEntity unit, String action) {
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof ProjectileEntity p
          && p.getTarget() == unit
          && !p.getData().allowResetTarget()) {
        throw new UnsupportedOperationException(
            action
                + " warps "
                + unit.name()
                + " with "
                + p.name()
                + " aimed at it, whose row keeps its target through the reset, not modelled");
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
        int lane =
            LaneAssignment.lane(
                tileMap.width(),
                tileMap.height(),
                tileMap.width(),
                arguments.x(),
                arguments.y(),
                -1,
                0,
                tileMap::bits);
        for (WorldObserver observer : observers) {
          observer.ringLaneAsked(tick, source, arguments.x(), arguments.y(), lane);
        }
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
      // The child is registered now and joins the live list at the next cleanup's fold, which
      // starts it: its row's starting action is scheduled then.
      child.startOnAdmission();
      holder.addRegistered(child);
      if (arguments.deathSpawn() && DEATH_SPAWN_IMMUNE_FIRST_TICK) {
        child.startSpawnImmunity();
      }
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, source, child, x, y);
      }
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
    for (WorldObserver observer : observers) {
      observer.characterSpawned(tick, source, child, cx, cy);
    }
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
    for (WorldObserver observer : observers) {
      observer.characterSpawned(tick, unit, child, x, y);
    }
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
      for (WorldObserver observer : observers) {
        observer.projectilesDropped(tick, unit, dropped);
      }
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

  /** Tells the observers the hold, layer and contact tags of an entity's word changed. */
  void tagWordChanged(WorldEntity entity, long word) {
    for (WorldObserver observer : observers) {
      observer.tagWordChanged(tick, entity, word);
    }
  }

  /** Tells the observers an uppercut started on a unit. */
  void uppercutStarted(
      CharacterEntity unit,
      String action,
      int phase,
      WorldEntity instigator,
      WorldEntity target,
      boolean finished) {
    for (WorldObserver observer : observers) {
      observer.uppercutStarted(tick, unit, action, phase, instigator, target, finished);
    }
  }

  /** Tells the observers an uppercut's update changed its delay, pushed or finished. */
  void uppercutStepped(
      CharacterEntity unit, int delay, boolean finished, String outcome, int[] pushPoint) {
    for (WorldObserver observer : observers) {
      observer.uppercutStepped(tick, unit, delay, finished, outcome, pushPoint);
    }
  }

  /**
   * Tells the observers an uppercut marked its target, or a taunt its forced object, in the unit's
   * targeting queue.
   */
  void uppercutMarked(CharacterEntity unit, WorldEntity target, int priority) {
    for (WorldObserver observer : observers) {
      observer.uppercutMarked(tick, unit, target, priority, unit.currentTarget());
    }
  }

  /** Tells the observers a unit's pre-hook emptied its targeting queue. */
  void targetQueueFlushed(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.targetQueueFlushed(tick, unit);
    }
  }

  /** Tells the observers an uppercut's target left the battle. */
  void uppercutTargetLeft(CharacterEntity unit, WorldEntity target) {
    for (WorldObserver observer : observers) {
      observer.uppercutTargetLeft(tick, unit, target);
    }
  }

  /** Tells the observers a knock started on a unit. */
  void knockbackStarted(
      CharacterEntity unit, String action, int phase, WorldEntity instigator, int counter) {
    for (WorldObserver observer : observers) {
      observer.knockbackStarted(tick, unit, action, phase, instigator, counter);
    }
  }

  /** Tells the observers a knock's update ran. */
  void knockbackStepped(
      CharacterEntity unit, int before, int after, int height, long tags, boolean finished) {
    for (WorldObserver observer : observers) {
      observer.knockbackStepped(tick, unit, before, after, height, tags, finished);
    }
  }

  /** Tells the observers a resetable action made its area effect. */
  void resetableStarted(
      CharacterEntity unit,
      int phase,
      WorldEntity instigator,
      AreaEffectEntity areaEffect,
      int x,
      int y) {
    for (WorldObserver observer : observers) {
      observer.resetableStarted(tick, unit, phase, instigator, areaEffect, x, y);
    }
  }

  /** Tells the observers a resetable action's run ended after its area effect left. */
  void resetableEnded(CharacterEntity unit, String areaEffect) {
    for (WorldObserver observer : observers) {
      observer.resetableEnded(tick, unit, areaEffect);
    }
  }

  /**
   * Tells the observers a resetable action's singleton row was started again: the countdown its
   * area effect got back, or null with none live.
   */
  void resetableRetriggered(CharacterEntity unit, int phase, String areaEffect, Integer countdown) {
    for (WorldObserver observer : observers) {
      observer.resetableRetriggered(tick, unit, phase, areaEffect, countdown);
    }
  }

  /** Tells the observers a resetable action's area effect left. */
  void resetableLeft(CharacterEntity unit, String areaEffect) {
    for (WorldObserver observer : observers) {
      observer.resetableLeft(tick, unit, areaEffect);
    }
  }

  /** Tells the observers a resetable action's area effect had its life cut as the unit left. */
  void resetableReleased(CharacterEntity unit, String areaEffect, int before, int after) {
    for (WorldObserver observer : observers) {
      observer.resetableReleased(tick, unit, areaEffect, before, after);
    }
  }

  /** Tells the observers what a shaped area effect listed. */
  void shapeListed(AreaEffectEntity areaEffect, List<WorldEntity> listed) {
    for (WorldObserver observer : observers) {
      observer.shapeListed(tick, areaEffect, listed);
    }
  }

  /** Tells the observers a choice by team ran on an entity. */
  void filteredByTeam(
      WorldEntity unit, String action, SpawnHost instigator, boolean sameTeam, String chosen) {
    for (WorldObserver observer : observers) {
      observer.filteredByTeam(tick, unit, action, instigator, sameTeam, chosen);
    }
  }

  /** Tells the observers a run that waited for its cause to leave scheduled its action. */
  void instigatorGone(WorldEntity unit, String action, String scheduled) {
    for (WorldObserver observer : observers) {
      observer.instigatorGone(tick, unit, action, scheduled);
    }
  }

  /** Tells the observers an action run at an age was scheduled on an area effect. */
  void aliveTimerFired(AreaEffectEntity areaEffect, String action) {
    for (WorldObserver observer : observers) {
      observer.aliveTimerFired(tick, areaEffect, action);
    }
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
   * Tells the observers a unit's ability spent its souls on its area effect, whose lifetime they
   * set.
   */
  void soulsSpent(
      CharacterEntity unit, AreaEffectEntity areaEffect, int souls, int count, int lifetimeMs) {
    for (WorldObserver observer : observers) {
      observer.soulsSpent(tick, unit, areaEffect, souls, count, lifetimeMs);
    }
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
    for (WorldObserver observer : observers) {
      observer.spawnOrdered(tick, areaEffect, order.clone(), before, random.getState());
    }
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
    for (WorldObserver observer : observers) {
      observer.characterSpawned(tick, areaEffect, child, x, y);
      observer.areaSpawned(tick, areaEffect, child, retries, random.getState());
    }
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

  /**
   * The building placement a spawn row that validates its point as a building's asks for: the point
   * clamped into the arena, kept when no building of the live list other than the owner overlaps
   * the child's circle there and its cell can be stood on. The owner is the object whose action
   * runs the row, so a building that places a building where it stands, as the hero Musketeer's
   * dummy building places the turret, does not block its own point. A blocked point is refused: the
   * search over the rows toward or away from the side that would follow, whose overlap test leaves
   * no object out, is not modelled.
   *
   * @param owner the object whose action spawns the child
   * @param child the row of the child
   * @return the search
   */
  SpawnPerform.PlacementSearch buildingPlacement(WorldEntity owner, UnitData child) {
    return (x, y) -> {
      int clampedX = Math.max(0, Math.min(x, tileMap.width() * TileMap.CELL_UNITS - 1));
      int clampedY = Math.max(0, Math.min(y, tileMap.height() * TileMap.CELL_UNITS - 1));
      if (!buildingOver(clampedX, clampedY, child.collisionRadius(), owner)
          && CellTests.cellBlocked(grid, clampedX, clampedY) == 0) {
        return new int[] {clampedX, clampedY};
      }
      throw new UnsupportedOperationException(
          child.name()
              + " is placed as a building at ("
              + x
              + ", "
              + y
              + "), which is blocked; the search for a free row is not modelled");
    };
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

  /** Tells the observers a unit's ability held it in its follow-up state. */
  void abilityStateEntered(CharacterEntity unit, int countdown) {
    for (WorldObserver observer : observers) {
      observer.abilityStateEntered(tick, unit, countdown);
    }
  }

  /** Tells the observers a unit's ability sent it across the arena. */
  void lanesSwitched(
      CharacterEntity unit, int mirroredX, int mirroredY, int toX, int toY, TargetView reference) {
    for (WorldObserver observer : observers) {
      observer.lanesSwitched(tick, unit, mirroredX, mirroredY, toX, toY, reference);
    }
  }

  /** Tells the observers what a check of an action's cause found. */
  void instigatorChecked(
      WorldEntity owner, String action, ActionOwner instigator, String scheduled) {
    for (WorldObserver observer : observers) {
      observer.instigatorChecked(tick, owner, action, instigator, scheduled);
    }
  }

  /** Tells the observers what a Berserker's run did to its unit's attack sequence index. */
  void berserked(WorldEntity unit, Berserk.Event event, int before, int index) {
    for (WorldObserver observer : observers) {
      observer.berserked(tick, unit, event, before, index);
    }
  }

  /** Tells the observers what a Goblin Hut's life state did. */
  void goblinHutLogged(CharacterEntity hut, GoblinHutLifeState.Event event) {
    for (WorldObserver observer : observers) {
      observer.goblinHutLogged(tick, hut, event);
    }
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
    for (WorldObserver observer : observers) {
      observer.championHandedOver(tick, source, champion);
    }
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
   * whose pull a moving area effect gates by an angle.
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
    for (WorldObserver observer : observers) {
      observer.areaEffectCreated(tick, areaEffect, how, source);
    }
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
   * <p>Refused rather than guessed: a source that is a clone, whose clone byte the area effect
   * would copy, which nothing the battle models reads.
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
    if (source instanceof CharacterEntity unit && unit.isClone()) {
      throw new UnsupportedOperationException(
          action + " spawns " + row + " from a clone, which is not modelled");
    }
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
    for (WorldObserver observer : observers) {
      observer.areaEffectSpawned(tick, owner, action, phase, source, areaEffect);
    }
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
    for (WorldObserver observer : observers) {
      observer.actionProjectileLaunched(tick, owner, action, phase, projectile);
    }
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
   * Tells every observer that a projectile admitted to the battle scheduled its starting action.
   */
  public void projectileStarting(ProjectileEntity projectile, String action) {
    for (WorldObserver observer : observers) {
      observer.projectileStarting(tick, projectile, action);
    }
  }

  /** Tells every observer that a data-changing action swapped a projectile's row. */
  public void projectileSwapped(ProjectileEntity projectile, String from, String to) {
    for (WorldObserver observer : observers) {
      observer.projectileSwapped(tick, projectile, from, to);
    }
  }

  /** Tells every observer that the evolved Executioner's axe controller started on its axe. */
  public void executionerStarted(ProjectileEntity axe, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.executionerStarted(tick, axe, action, phase);
    }
  }

  /**
   * Tells every observer of a damage the axe controller was asked for.
   *
   * @param target what the hit lands on, or null
   * @param before the damage handed in
   * @param after the damage handed on
   * @param edge the target's distance from the axe's start less its radius, or null without one
   * @param strong true for a strong hit
   */
  public void axeDamage(
      ProjectileEntity axe,
      WorldEntity target,
      int hitId,
      int before,
      int after,
      Integer edge,
      boolean strong) {
    for (WorldObserver observer : observers) {
      observer.axeDamage(tick, axe, target, hitId, before, after, edge, strong);
    }
  }

  /** Tells every observer that a strong hit of the axe scheduled its action on its target. */
  public void axeHitAction(ProjectileEntity axe, WorldEntity target, String action, int hitId) {
    for (WorldObserver observer : observers) {
      observer.axeHitAction(tick, axe, target, action, hitId);
    }
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
    for (WorldObserver observer : observers) {
      observer.axePushed(tick, axe, target, x, y, distance, hitId);
    }
    character.pushedFrom(x, y, distance);
  }

  /** A roll started on a projectile, with the destination it took. */
  public void rollStarted(
      ProjectileEntity projectile, String action, int phase, int destinationX, int destinationY) {
    for (WorldObserver observer : observers) {
      observer.rollStarted(tick, projectile, action, phase, destinationX, destinationY);
    }
  }

  /** A capture started on its owner, a projectile or a character. */
  public void captureStarted(BattleEntity owner, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.captureStarted(tick, owner, action, phase);
    }
  }

  /** The not-placeable value alone, which a cell blocks a roll's destination with. */
  private static final int ROLL_BLOCKING_CELL = TileMap.NOT_PLACEABLE_BIT;

  /**
   * The tags a capture's drag raises on its unit for one step. NO_REFLECTED_ATTACK adds nothing
   * under a tags table that does not list it.
   */
  long captureTags() {
    return flagBits.noDash()
        | flagBits.buildingDeathSpawnFindLocation()
        | flagBits.abilityDisabled()
        | flagBits.noReflectedAttack()
        | flagBits.captured();
  }

  /** The tags a capture's drag raises on its unit for one step besides, without a capture buff. */
  long captureHoldTags() {
    return flagBits.noMove() | flagBits.noSummon() | flagBits.noAttack();
  }

  /**
   * The deflection pass a roll runs at its projectile's point as it starts and after each step,
   * which finds nothing without a deflecting area effect; one in the battle is refused.
   *
   * @param projectile the rolling projectile
   */
  public void rollDeflectionPass(ProjectileEntity projectile) {
    if (!deflectors().isEmpty()) {
      throw new UnsupportedOperationException(
          projectile.name() + " rolls beside a deflecting area effect, which is not modelled");
    }
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
    for (WorldObserver observer : observers) {
      observer.rollBuffed(tick, projectile, target, buff, timeMs);
    }
    target
        .getBuffs()
        .apply(
            records.buff(buff), timeMs, projectile.getPackedLevel(), projectile, projectile.side());
  }

  /**
   * A roll moved its projectile, or put it on its destination and released it.
   *
   * @param projectile the rolling projectile
   * @param released true for the step that released it
   */
  public void rolled(ProjectileEntity projectile, boolean released) {
    for (WorldObserver observer : observers) {
      observer.rolled(tick, projectile, released);
    }
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

  /** A capture asked for a lock on a unit, with the request's answer. */
  public void captureRequested(BattleEntity owner, WorldEntity unit, int priority, boolean answer) {
    for (WorldObserver observer : observers) {
      observer.captureRequested(tick, owner, unit, priority, answer);
    }
  }

  /** A capture scheduled an action on an object, with another as its cause. */
  public void captureScheduled(BattleEntity owner, BattleEntity cause, String action) {
    for (WorldObserver observer : observers) {
      observer.captureScheduled(tick, owner, cause, action);
    }
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
   * besides for a capture without a buff. Refused: a unit whose death spawns a building, and a
   * reflecting unit in the battle, whose readers of two of the tags are not modelled, and a unit
   * with a spawner held without a buff, whose reader of NO_SUMMON is not modelled.
   *
   * @param unit the unit dragged
   * @param withoutBuff true for a capture whose row gives no capture buff
   */
  public void captureTagged(WorldEntity unit, boolean withoutBuff) {
    if (withoutBuff && unit.getData().spawnCharacter() != null) {
      throw new UnsupportedOperationException(
          unit.name() + " is captured without a buff and has a spawner, not modelled");
    }
    String deathSpawn = unit.getData().deathSpawnCharacter();
    if (deathSpawn != null && records.unit(deathSpawn).building()) {
      throw new UnsupportedOperationException(
          unit.name() + " is captured and its death spawns a building, not modelled");
    }
    for (BattleEntity entity : holder.entities()) {
      if (entity instanceof WorldEntity other && other.getData().reflectedAttackRadius() >= 1) {
        throw new UnsupportedOperationException(
            unit.name() + " is captured beside the reflecting " + other.name() + ", not modelled");
      }
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
   * capturing character the attacker, passing the hidden unit.
   *
   * @param owner the capturing character
   * @param unit the unit held
   * @param damage the damage per hit at the owner's level
   */
  public void captureHit(CharacterEntity owner, WorldEntity unit, int damage) {
    unit.takeDamage(damage, 0, 0, 0, true, owner, owner);
  }

  /** A captured unit's pre-hook changed its hidden tag or the capture's tags in its word. */
  void captureTagsFolded(WorldEntity unit, boolean hidden, long word) {
    for (WorldObserver observer : observers) {
      observer.captureTagsFolded(tick, unit, hidden, word);
    }
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
   * A completed drag: the unit put on the owner's point and, with a movement component, its jump
   * ended, its route reset and, with a height change, the change pushed with its floor, which the
   * next pre-hook folds. A unit in a jump, or a dash with a height, would be put down first, which
   * is refused.
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
      int state = unit.getView().getState();
      if (state == GridEntityState.JUMPING
          || (state == GridEntityState.DASHING && unit.getData().jumpHeight() >= 1)) {
        throw new UnsupportedOperationException(
            owner + " captures " + unit.name() + " in the air, not modelled");
      }
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
   * A capture's step ended.
   *
   * @param owner the capturing owner
   * @param captured the ids it holds
   * @param complete the ids whose drag is complete
   * @param timesMs the time of each capture
   */
  public void captureStepped(
      BattleEntity owner, List<Integer> captured, List<Integer> complete, List<Integer> timesMs) {
    for (WorldObserver observer : observers) {
      observer.captureStepped(
          tick, owner, List.copyOf(captured), List.copyOf(complete), List.copyOf(timesMs));
    }
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
    return createAreaEffect(
        row, x, y, owner.side(), owner.getPackedLevel(), null, "barrage", owner.name());
  }

  /** Tells every observer that a barrage's run started on a character. */
  void barrageStarted(CharacterEntity owner, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.barrageStarted(tick, owner, action, phase);
    }
  }

  /** Tells every observer of the area effects a barrage's update made. */
  void barrageStepped(CharacterEntity owner, String action, List<AreaEffectEntity> made) {
    for (WorldObserver observer : observers) {
      observer.barrageStepped(tick, owner, action, List.copyOf(made));
    }
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
    for (WorldObserver observer : observers) {
      observer.bombDropped(tick, area, action.name(), phase, projectile, lifetime);
    }
  }

  /** Counts one more character made, which a summon's name carries. */
  void characterMade() {
    charactersMade++;
  }

  /** Tells the observers an evolved Royal Ghost's run started. */
  void ghostEvoStarted(CharacterEntity ghost, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.ghostEvoStarted(tick, ghost, action, phase);
    }
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
    for (WorldObserver observer : observers) {
      observer.ghostAreaMade(tick, ghost, area, target);
    }
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

  /** Tells the observers an evolved Royal Ghost's hit summoned. */
  void ghostSummoned(CharacterEntity ghost, WorldEntity reference, int x, int y, int countdownMs) {
    for (WorldObserver observer : observers) {
      observer.ghostSummoned(tick, ghost, reference, x, y, countdownMs);
    }
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
    for (WorldObserver observer : observers) {
      observer.ghostSummonMade(tick, area, child);
    }
    child.takeSummonReference(reference);
    child.faceToward(x, y);
    child.summonReveal();
    for (WorldObserver observer : observers) {
      observer.ghostSummonSpawned(tick, area, child);
    }
  }

  /**
   * Makes a target indicator attack's signal: the area effect of the row at the target's point, for
   * the unit's side and at its level, re-based on the area effect's own rarity, the unit kept as
   * its parent and following nothing, handed to the holder with the unit's id as its maker. It is
   * admitted at the tick's closing cleanup and first updates on the next tick.
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
    signal.setCreator(unit.getId());
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

  /** Tells the observers what a target indicator attack's run did. */
  void targetIndicatorLogged(CharacterEntity unit, TargetIndicatorAttack.Event event) {
    for (WorldObserver observer : observers) {
      observer.targetIndicatorLogged(tick, unit, event);
    }
  }

  /** Tells the observers a taunt's perform reached a character. */
  void tauntPerformed(
      WorldEntity unit, String action, int phase, ActionOwner instigator, WorldEntity forced) {
    for (WorldObserver observer : observers) {
      observer.tauntPerformed(tick, unit, action, phase, instigator, forced);
    }
  }

  /** Tells the observers what a taunt's arming or step did. */
  void tauntStepped(
      WorldEntity unit, WorldEntity forced, int durationMs, int falloffMs, List<String> calls) {
    for (WorldObserver observer : observers) {
      observer.tauntStepped(tick, unit, forced, durationMs, falloffMs, calls);
    }
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
    for (WorldObserver observer : observers) {
      observer.areaBuff(tick, areaEffect, buff, time, targets);
    }
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
   * character must be alive, not untouchable and not waiting to deploy. Unlike an area effect it
   * has no filter of its own, so the air and the ground are reached alike.
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
    for (WorldObserver observer : observers) {
      observer.projectileBuff(tick, projectile, buff, time, targets);
    }
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
   * of such a hit as well, which no reference holds, so it is refused.
   *
   * @param attacker the entity whose hit it is
   * @param target the view the hit resolved against
   * @param result what the hit's damage did
   */
  private void drainBuffOnDamage(WorldEntity attacker, TargetView target, DamageResult result) {
    if (!result.accepted() || attacker.getData().buffOnDamage() == null) {
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
   * attacker as the source, at its level and for its side, for its row's BuffOnDamageTime. There is
   * no alive test, so a target the hit has just killed takes it too and leaves at the tick's
   * closing cleanup; the buff decides for itself whether the target takes it. A second hit, of the
   * same attack or another attacker, refreshes the one instance.
   *
   * @param attacker the entity whose hit it is
   * @param target what the hit reached
   */
  void buffOnDamage(WorldEntity attacker, WorldEntity target) {
    UnitData data = attacker.getData();
    BuffData buff = buffData(data.buffOnDamage());
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
              data.buffOnDamageTimeMs(),
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
   * deals their shares, so only what the area damaged takes it. Applied with the projectile as the
   * source, at its level and for its side, for the row's buff time at that level; the drain asks
   * nothing of the victim's dash immunity, which the damage entry has answered already.
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
    for (WorldObserver observer : observers) {
      observer.projectileBuff(tick, projectile, buff, time, List.of(victim));
    }
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
    for (WorldObserver observer : observers) {
      observer.projectileBuff(tick, projectile, buff, time, targets);
    }
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
   * source's side, with the source as the buff's.
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
    for (WorldObserver observer : observers) {
      observer.buffSpawned(tick, owner, action, data, timeMs, source.packedLevel(), source);
    }
    owner.getBuffs().apply(data, timeMs, source.packedLevel(), source, source.side(), parent);
  }

  /**
   * A Clone's creator: makes a clone of a unit on the unit's point, kept inside the arena, of the
   * unit's side and of its row, or of the row its row names as its cloned version, at the Clone's
   * level re-based on that row's rarity, named after the unit and how many clones of it came
   * before. The clone setter gives it 1 hit point of 1; it is set up as a clone through its setter,
   * which with the combat gate after it leaves its movement component on and its targeting
   * component off, faces as the unit faces, and is handed to the holder with its registration visit
   * - in the clone state with no route, which moves nothing - and joins the live list at the tick's
   * closing cleanup, whose fold starts it as any spawn: its own row's starting action is scheduled
   * then. The unit's runs stay the unit's. It takes a copy of every buff the unit carries, the
   * Clone's own buff, just put on the unit, among them, with the time each has left. Then the two
   * move apart: the clone back toward its own side, and the unit, set up as a clone too unless it
   * dashes, forward. Last, for a unit that carries riders, each rider in their order is cloned the
   * same way, through this creator - its clone made on the rider's point, the rider set up as a
   * clone and moving apart too - and its clone attached to the unit's clone on the rider's angle. A
   * clone never enters the deploying state, so the unit's clone makes no riders of its own.
   *
   * @param original the unit
   * @param instigator the Clone's area effect
   * @param action the Clone's action
   * @return the clone
   */
  CharacterEntity makeClone(CharacterEntity original, AreaEffectEntity instigator, Clone action) {
    UnitData row = original.getData();
    // The clone's row: the unit's own, or the row its row names as its cloned version.
    UnitData cloneRow = row.clonedVersion() != null ? records.unit(row.clonedVersion()) : row;
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
    clone.enterCloneSetup();
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
    for (WorldObserver observer : observers) {
      observer.cloned(tick, original, clone, instigator, visits);
    }
    clone.getBuffs().copyFrom(original.getBuffs());
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
        for (WorldObserver observer : observers) {
          observer.riderAttached(tick, clone, riderClone, i, rider.getAttachAngle());
        }
      }
    }
    return clone;
  }

  /**
   * A clone's spawn is a clone: the clone setter on the child, the spawner as the unit it stands
   * for. The spawner calls it last, after the child's deploying state and deploy countdown, and it
   * sets only the clone mark and the hit points, so a child with a deploy time is a clone made
   * deploying: its countdown runs as any unit's, it takes no part in collision until the countdown
   * ends (the contact rule's deploying clone), and it resumes as any unit then.
   */
  private static void cloneSpawn(WorldEntity spawner, CharacterEntity child) {
    if (!(spawner instanceof CharacterEntity source) || !source.isClone()) {
      return;
    }
    child.markClone(spawner);
  }

  void cloneRefused(WorldEntity original, String reason, SpawnHost instigator) {
    for (WorldObserver observer : observers) {
      observer.cloneRefused(tick, original, reason, instigator);
    }
  }

  void buffCopied(WorldEntity original, WorldEntity clone, BuffInstance copy) {
    for (WorldObserver observer : observers) {
      observer.buffCopied(tick, original, clone, copy);
    }
  }

  void cloneBuffGateAsked(
      AreaEffectEntity areaEffect,
      String buff,
      CharacterEntity clone,
      String path,
      int query,
      boolean refused) {
    for (WorldObserver observer : observers) {
      observer.cloneBuffGateAsked(tick, areaEffect, buff, clone, path, query, refused);
    }
  }

  void cloneMoveStarted(CharacterEntity unit, int targetX, int targetY) {
    for (WorldObserver observer : observers) {
      observer.cloneMoveStarted(tick, unit, targetX, targetY);
    }
  }

  void cloneMoveEnded(CharacterEntity unit, boolean resumed) {
    for (WorldObserver observer : observers) {
      observer.cloneMoveEnded(tick, unit, resumed);
    }
  }

  /** The battle tick of the entity tick in progress. */
  public int tick() {
    return tick;
  }

  /** The bit of FORCE_IS_GROUND in an entity's tag word, as the data numbers the game tags. */
  long forceIsGround() {
    return flagBits.forceIsGround();
  }

  /** The bit of FORCE_IS_AIR in an entity's tag word, as the data numbers the game tags. */
  long forceIsAir() {
    return flagBits.forceIsAir();
  }

  void selectorStarted(AreaEffectEntity areaEffect, String action, int phase, List<Integer> due) {
    for (WorldObserver observer : observers) {
      observer.selectorStarted(tick, areaEffect, action, phase, due);
    }
  }

  void selectorStepped(AreaEffectEntity areaEffect, String action, ShapeSelector.Step step) {
    for (WorldObserver observer : observers) {
      observer.selectorStepped(tick, areaEffect, action, step);
    }
  }

  void airToGroundStarted(
      WorldEntity unit, String action, int phase, int phaseNow, int counter, int height) {
    for (WorldObserver observer : observers) {
      observer.airToGroundStarted(tick, unit, action, phase, phaseNow, counter, height);
    }
  }

  void airToGroundStepped(
      WorldEntity unit,
      int phaseBefore,
      int phaseAfter,
      int counterBefore,
      int counterAfter,
      boolean done,
      List<Integer> pushes) {
    for (WorldObserver observer : observers) {
      observer.airToGroundStepped(
          tick, unit, phaseBefore, phaseAfter, counterBefore, counterAfter, done, pushes);
    }
  }

  void airToGroundRetriggered(WorldEntity unit, String action, int phase, int counter) {
    for (WorldObserver observer : observers) {
      observer.airToGroundRetriggered(tick, unit, action, phase, counter);
    }
  }

  void groundToAirStarted(WorldEntity unit, String action, int phase, int phaseNow, int counter) {
    for (WorldObserver observer : observers) {
      observer.groundToAirStarted(tick, unit, action, phase, phaseNow, counter);
    }
  }

  void groundToAirStepped(
      WorldEntity unit,
      int phaseBefore,
      int phaseAfter,
      int counterBefore,
      int counterAfter,
      List<Integer> pushes) {
    for (WorldObserver observer : observers) {
      observer.groundToAirStepped(
          tick, unit, phaseBefore, phaseAfter, counterBefore, counterAfter, pushes);
    }
  }

  void laserStarted(AreaEffectEntity areaEffect, String action, int phase, int timerMs) {
    for (WorldObserver observer : observers) {
      observer.laserStarted(tick, areaEffect, action, phase, timerMs);
    }
  }

  void laserFired(
      AreaEffectEntity areaEffect,
      int count,
      int index,
      List<WorldEntity> targets,
      String action,
      int timerBefore,
      int timerAfter) {
    for (WorldObserver observer : observers) {
      observer.laserFired(tick, areaEffect, count, index, targets, action, timerBefore, timerAfter);
    }
  }

  void lifeTimeEndScheduled(AreaEffectEntity areaEffect, String action) {
    for (WorldObserver observer : observers) {
      observer.lifeTimeEndScheduled(tick, areaEffect, action);
    }
  }

  void onHitActionScheduled(AreaEffectEntity areaEffect, WorldEntity target, BattleAction action) {
    for (WorldObserver observer : observers) {
      observer.onHitActionScheduled(tick, areaEffect, target, action);
    }
  }

  void buffHookScheduled(WorldEntity carrier, BuffInstance buff, String action, boolean start) {
    for (WorldObserver observer : observers) {
      observer.buffHookScheduled(tick, carrier, buff, action, start);
    }
  }

  void hitCounted(
      WorldEntity attacker, WorldEntity target, int before, int after, String buff, int timeMs) {
    for (WorldObserver observer : observers) {
      observer.hitCounted(tick, attacker, target, before, after, buff, timeMs);
    }
  }

  void buffApplied(WorldEntity target, BuffInstance buff) {
    for (WorldObserver observer : observers) {
      observer.buffApplied(tick, target, buff);
    }
  }

  void buffHandedOver(
      WorldEntity parent,
      WorldEntity rider,
      BuffData buff,
      int time,
      int packedLevel,
      SpawnHost source,
      List<BuffInstance> instances,
      Set<String> heldBefore) {
    for (WorldObserver observer : observers) {
      observer.buffHandedOver(
          tick, parent, rider, buff, time, packedLevel, source, instances, heldBefore);
    }
  }

  void buffRefreshed(WorldEntity target, BuffInstance buff, int before, SpawnHost source) {
    for (WorldObserver observer : observers) {
      observer.buffRefreshed(tick, target, buff, before, source);
    }
  }

  void attackCountRead(WorldEntity context, int attackTimeMs, int count) {
    for (WorldObserver observer : observers) {
      observer.attackCountRead(tick, context, attackTimeMs, count);
    }
  }

  void lifeConditionAsked(WorldEntity carrier, BuffInstance buff, int answer) {
    for (WorldObserver observer : observers) {
      observer.lifeConditionAsked(tick, carrier, buff, answer);
    }
  }

  void buffRemoved(WorldEntity target, BuffInstance buff) {
    for (WorldObserver observer : observers) {
      observer.buffRemoved(tick, target, buff);
    }
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
   * death it causes, with nothing as what killed it.
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
    for (WorldObserver observer : observers) {
      observer.buffDamaged(tick, target, due.buff(), due.damage(), before, result);
    }
    if (result.died()) {
      // The killing side is the one the buff was applied for.
      target.die(null, due.side());
    }
  }

  /**
   * Gives one heal of a buff's heal over time to its entity, capped by the buff's over-heal share,
   * and tells the observers.
   */
  void dealBuffHeal(WorldEntity target, BuffInstance buff, int heal) {
    int before = target.getHitPoints().getHitPoints();
    target.takeHeal(heal, buff.getBuff().allowedOverHealPercent());
    for (WorldObserver observer : observers) {
      observer.buffHealed(tick, target, buff, heal, before);
    }
  }

  /** Tells the observers which units an area effect's hit pulled. */
  void areaPulled(AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulls) {
    for (WorldObserver observer : observers) {
      observer.areaPulled(tick, areaEffect, pulls);
    }
  }

  /**
   * Tells the observers of an area effect's launch on a step whose hit count rose.
   *
   * @param areaEffect the area effect
   * @param hit the hits due by the end of the step
   * @param bound the hits due by its start
   * @param choice what its chooser saw, or null for a row that drops its projectile on its point
   * @param projectile the projectile launched, or null when the chooser found nobody
   */
  void areaEffectLaunched(
      AreaEffectEntity areaEffect,
      int hit,
      int bound,
      AreaEffectEntity.Choice choice,
      ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.areaEffectLaunched(tick, areaEffect, hit, bound, choice, projectile);
    }
  }

  /** Tells the observers an area effect was admitted. */
  void areaEffectAdmitted(AreaEffectEntity areaEffect) {
    for (WorldObserver observer : observers) {
      observer.areaEffectAdmitted(tick, areaEffect);
    }
  }

  /** Tells the observers an area effect updated. */
  void areaEffectUpdated(
      AreaEffectEntity areaEffect,
      int before,
      int after,
      int hits,
      int radius,
      List<Integer> damages) {
    for (WorldObserver observer : observers) {
      observer.areaEffectUpdated(tick, areaEffect, before, after, hits, radius, damages);
    }
  }

  /** Tells the observers what one hit of an area effect did. */
  void areaEffectDamaged(
      AreaEffectEntity areaEffect, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    for (WorldObserver observer : observers) {
      observer.areaEffectDamaged(tick, areaEffect, area, outcome);
    }
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
    for (WorldObserver observer : observers) {
      observer.areaEffectHit(tick, areaEffect, victim, damage, result);
    }
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
    List<WorldEntity> snapshotOfPresent = present();
    List<ProjectileEntity> snapshotOfProjectiles = projectiles();
    for (WorldObserver observer : observers) {
      observer.afterPostHooks(tick, snapshotOfPresent, snapshotOfProjectiles);
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
    indexedDeflectors.clear();
  }
}
