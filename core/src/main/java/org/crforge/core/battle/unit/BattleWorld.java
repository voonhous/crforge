package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
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
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.data.ActionRows;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.Formation;
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.filter.FilterSubject;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileChain;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.projectile.ProjectileLauncher;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.battle.spawn.SpawnPassable;
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
import org.crforge.core.pathfinding.grid.FootprintOverlay;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Relocation;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.index.ShapeTests;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.index.SpatialQuery;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.MovementGlobals;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.NeighbourQuery;
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
            + " snapshot of the arena entities and retired in the post-pass, the overlay's per-side"
            + " change flags are copied once per tick, a projectile is in neither and is handed to"
            + " the holder in the tick of its launch, and a dead entity leaves the holder in the"
            + " closing cleanup of the tick it dies - the king tower excepted, which never does -"
            + " when every arena entity is told at once and its default target lists lose it; the"
            + " death handler scheduling the death action and, unless the entity killed itself,"
            + " the killed action on the dying entity with its killer as the cause, and a"
            + " champion handed over after its spawn with no effect on it; the death slot inside"
            + " the killing hit - the death damage around the dying entity with its pushback, and"
            + " the death spawn on its ring - held by golemite_convert, golemite_death_damage and"
            + " tombstone_death_hook; the death spawn's children flying back to the ring, held by"
            + " golem_death_pushback; a ring turned over by the dying object's lane and team, its"
            + " children given a fixed priority, held by skeleton_barrel_tower and"
            + " skeleton_barrel_shot_down; a single child on the dying object's point and a bomb's"
            + " death slot as its deploy ends, without the death hooks, held by"
            + " giant_skeleton_bomb; several death spawn children in front of the dying object,"
            + " a lifetime's death without the death handler and a building spawner's children in"
            + " front of it, held by tombstone_life and goblin_hut_life; an area effect created by"
            + " a death or placed directly and its hits dealt, held by area_effect_direct and"
            + " area_effect_death; a troop card's projectile cast before its units and a unit's"
            + " push as it enters the deploying state, finding nobody in a card play's command"
            + " pass, held by mega_knight_group and mega_knight_jump, and the push's tests on what"
            + " it finds by a unit that waits its turn, held by no run. Refused: a death whose row"
            + " sets a column of the slot not"
            + " modelled, a least radius that draws, a child without hit points that has a range, a"
            + " spawner's turned or drawn ring and a spawner child without hit points, a building,"
            + " pathing or starting an action, and a death hook with no attacker or no pending"
            + " pass ahead of it. Left out: the elixir a death gives. Not"
            + " modelled: the game mode's own per-tick work beside the index and the overlay, and"
            + " the copy of the attacker the game makes as a death hook's cause.")
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
   * Every arena entity admitted so far and not yet gone, keyed by its view. An entity joins at its
   * first pre-pass and leaves at the cleanup that removes it.
   */
  private final Map<GridEntity, WorldEntity> known = new IdentityHashMap<>();

  /** The battle's hit counter: every hit takes the next id from it. */
  private int hitCounter;

  /** How many buff instances the battle has listed, which names the next. */
  private int buffKeys;

  /** The most victims a death's damage takes. */
  private static final int DEATH_DAMAGE_LIMIT = 1000;

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

  /** The variables the battle's expressions may name, by name, each with its key. */
  private final Map<String, Integer> variableKeys = new HashMap<>();

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
      int directionY) {}

  /** The typed hits dealt this tick, in the order they were dealt. */
  private final List<TypedHit> typedHits = new ArrayList<>();

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
    this.movementGlobals = MovementGlobals.forStandardArena(tileMap.width());
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
  Integer variableKey(String name) {
    return variableKeys.get(name);
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

  /** The battle's action rows, from the tables it was loaded with. */
  @Getter private ActionRows actions;

  /** The battle's target locks, made by the first collector's step; null until then. */
  private TargetLocks locks;

  /**
   * Loads the game's tables into the battle: declares their variables and game tags and keeps the
   * records and action rows built from them, which the battle's entities build their actions from.
   *
   * @param tables the game tables of one data version
   */
  public void load(GameTables tables) {
    declare(tables);
    this.records = new BattleRecords(tables);
    this.actions = new ActionRows(tables, records);
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
      registerVariable(row.name(), row.index());
    }
    for (GameRow row : tables.table("game_tags").rows()) {
      registerGameTag(row.name(), 1L << row.index());
    }
  }

  /**
   * What an action row built for an arena entity reads from it: its expressions compiled for it and
   * evaluated afresh each time, the battle's variable keys, its tag word and its spawn rate.
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
    };
  }

  /** The key of a declared variable; fails for a name the battle does not declare. */
  int declaredVariable(String name) {
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
    WorldEntity entity = known.get(target.getEntity());
    if (entity == null) {
      return DamageResult.NOTHING;
    }
    int before = hitPointsOf(entity);
    DamageResult result = entity.takeDamage(damage, 0, directionX, directionY);
    reflect(entity, attacker, before, result, directionX, directionY);
    for (WorldObserver observer : observers) {
      observer.damageDealt(tick, entity, damage, result);
    }
    if (result.died()) {
      entity.die(attacker);
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
    DamageResult hit = struck.takeReflectedDamage(damage, directionX, directionY);
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
   * The hook's end of a jump or a dash with a height on the target it pulls, before it is pulled:
   * such a target would be put down first, which no reference holds; any other is left alone.
   *
   * @param projectile the hooking projectile
   * @param target the target it hooked
   */
  public void putDown(ProjectileEntity projectile, WorldEntity target) {
    int state = target.getView().getState();
    if (state == GridEntityState.JUMPING
        || (state == GridEntityState.DASHING && target.getData().jumpHeight() >= 1)) {
      throw new UnsupportedOperationException(
          projectile.name() + " hooks " + target.name() + " in the air, not modelled");
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

  /** Tells every observer that a unit's targeting forgot the hooking projectile it was held on. */
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

  /** The battle's target locks, made by the first call, as a collector's first step makes them. */
  public TargetLocks locks() {
    if (locks == null) {
      locks = new TargetLocks();
    }
    return locks;
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
   * Kills an entity, and tells every observer what the kill did as they are told of a hit of its
   * whole hit points.
   *
   * @param target the entity
   * @param killer the entity that caused it, or null for none
   */
  public void kill(WorldEntity target, WorldEntity killer) {
    int before = target.getHitPoints() == null ? 0 : target.getHitPoints().getHitPoints();
    DamageResult result = target.takeKill();
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
   * @param old the unit that surfaced
   */
  void morph(CharacterEntity old) {
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
    UnitData data = spawnedRow(old.getData().spawnPathfindMorph());
    CharacterEntity made = CharacterEntity.morphedFrom(old, data);
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
   * on its own side, so its death action runs alone. Every observer is told of the kill.
   *
   * @param unit the unit
   */
  public void kamikazeKill(WorldEntity unit) {
    int before = unit.getHitPoints().getHitPoints();
    DamageResult result = unit.takeKill();
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
   * no attacker, told to every observer as the circle's kill rather than a hit.
   *
   * @param target the entity
   * @param radius the circle's radius
   */
  public void circleKill(WorldEntity target, int radius) {
    DamageResult result = target.takeKill();
    for (WorldObserver observer : observers) {
      observer.circleKilled(tick, target, radius);
    }
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Kills a character of either side that a tiebreaker's clearing reached: it is resumed first,
   * then takes its whole hit points with no attacker, told to every observer as the clearing's kill
   * rather than a hit.
   *
   * @param target the character
   */
  public void clearingKill(CharacterEntity target) {
    target.resume();
    DamageResult result = target.takeKill();
    for (WorldObserver observer : observers) {
      observer.clearingKilled(tick, target);
    }
    if (result.died()) {
      target.die(null);
    }
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
   * Deals one victim's share of the area of an entity's hit, and tells every observer what it did.
   * A victim that has left the battle takes nothing.
   *
   * @param attacker the entity whose hit made the area
   * @param victim the entity the area collected
   * @param damage hit points the area deals it, before its guards and the clamp to zero
   * @param hitId the id the hit carries
   * @return what the damage did to the victim
   */
  public DamageResult dealAreaDamage(
      WorldEntity attacker, WorldEntity victim, int damage, int hitId) {
    if (victim == null || known.get(victim.getView()) != victim) {
      return DamageResult.NOTHING;
    }
    // A character's area carries no dedupe id and no direction.
    int before = hitPointsOf(victim);
    DamageResult result = victim.takeDamage(damage, 0, 0, 0);
    reflect(victim, attacker, before, result, 0, 0);
    for (WorldObserver observer : observers) {
      observer.areaHit(tick, attacker, victim, damage, hitId, result);
    }
    if (result.died()) {
      victim.die(attacker);
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
    // A projectile carries no dedupe id unless it belongs to a group, which none here does.
    int before = hitPointsOf(target);
    DamageResult result = target.takeDamage(damage, 0, directionX, directionY);
    reflect(target, projectile, before, result, directionX, directionY);
    for (WorldObserver observer : observers) {
      observer.projectileImpacted(tick, projectile, target, damage, result);
    }
    if (result.died()) {
      target.die(projectile);
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
    // A child leaves its source's group as it is released, after every notice of the removal.
    if (gone instanceof CharacterEntity child) {
      CharacterEntity source = child.leaveGroup();
      if (source != null) {
        for (WorldObserver observer : observers) {
          observer.groupUnlinked(tick, source, child);
        }
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
   * <p>Then the death handler's hooks: the row's death action and, unless the entity killed itself,
   * its killed action, each built for the entity and scheduled on its own holder with the row's own
   * delay, what killed it as the cause: the unit for a direct hit or its area, the projectile
   * itself for an impact, the source of a typed hit, the killer of a kill. The entity stays in the
   * tick's snapshot until the closing cleanup, so a hook with no delay runs in the next pending
   * pass of the same tick: phase 2 after a hit in a component pass, phase 3 after an impact or a
   * typed hit, and at once, the death action before the killed action is scheduled, after a kill
   * inside a pending pass.
   *
   * <p>Refused rather than guessed: a death hook with no cause, which the game gives a cause that
   * carries only a side, and one scheduled after the tick's last pending pass, which would leave
   * with the entity. The cause is the attacker's own holder, where the game hands the hook a copy
   * of the attacker made at the kill; no hook built here reads it beyond its presence.
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
    deathHooks(dying, attacker, data);
    deathReward(dying, data, killingSide);
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

  /** The death handler's hooks: the dying unit's death and killed actions, with their cause. */
  private void deathHooks(WorldEntity dying, BattleEntity attacker, UnitData data) {
    if (data.onDeathAction() == null && data.onKilledAction() == null) {
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
              + " died with no attacker; the cause its death hooks would carry, a side alone, is"
              + " not modelled");
    }
    if (!holder.hasPendingPassAhead()) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died after the tick's last pending pass, where its death hooks would leave with"
              + " it; such a death is not established");
    }
    // A unit that killed itself runs its death action alone.
    boolean selfKill = attacker == dying && side == dying.side();
    List<String> hooks = new ArrayList<>();
    if (data.onDeathAction() != null) {
      hooks.add(data.onDeathAction());
    }
    if (!selfKill && data.onKilledAction() != null) {
      hooks.add(data.onKilledAction());
    }
    boolean inPendingPass = holder.isInPendingPass();
    for (WorldObserver observer : observers) {
      observer.deathHooksScheduled(tick, dying, attacker, side, List.copyOf(hooks), inPendingPass);
    }
    for (String hook : hooks) {
      // Built one at a time: inside a pending pass the death action runs before the killed action
      // is even scheduled.
      dying
          .actionHolder()
          .schedule(actions.build(hook, binding(dying)), ActionHolder.OWN_DELAY, false, cause);
    }
  }

  /**
   * The death of an object without hit points that its state visit removes - a bomb as its deploy
   * ends: what the object switches off, then its death slot, with no death handler after it, so no
   * hooks. It leaves at a later cleanup, once the visit has asked for its removal.
   *
   * @param dying the object
   */
  void deathAtRemoval(WorldEntity dying) {
    for (WorldObserver observer : observers) {
      observer.diedAtRemoval(tick, dying);
    }
    dying.died();
    deathSlot(dying, dying.getData());
  }

  /**
   * The death of a character whose lifetime decay took its last hit point, in its hit-points visit:
   * what it switches off, then its death slot, with no death handler after it. It leaves at the
   * closing cleanup of the tick. Then its death action, and not its killed action, is scheduled on
   * it with itself as the cause, and runs in the tick's next pending pass.
   *
   * @param dying the character
   * @param hitPointsBefore its hit points before the decay's last step
   */
  void decayDeath(WorldEntity dying, int hitPointsBefore) {
    UnitData data = dying.getData();
    for (WorldObserver observer : observers) {
      observer.decayDied(tick, dying, hitPointsBefore);
    }
    dying.died();
    deathSlot(dying, data);
    if (data.onDeathAction() != null) {
      // The death action alone, on itself with itself as the cause, taken by the tick's next
      // pending pass.
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
   * @param count how many children the firing makes
   * @param radius the ring's radius, or 0 to place in front
   */
  void liveSpawn(CharacterEntity spawner, int count, int radius) {
    UnitData data = spawner.getData();
    UnitData child = spawnedRow(data.spawnCharacter());
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
   * take the lower ids and every pass visits them before it. As many as the row's spawn number, on
   * the ring of its spawn radius - turned by its angle shift and facing when it sets one - or on
   * the parent's point with no radius, each at the parent's level re-based on its rarity, set
   * deploying for the parent's deploy time and facing as the parent faces, registered at once with
   * its registration visit, which sees no parent yet, and attached to the parent after it: from its
   * next movement visit it is placed around the parent.
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
      // Every entity accepted then would list the rider's id, the one write that fills an id list.
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
   */
  public void castSpell(DeployCard card, int cardLevel, int side, int x, int y, String name) {
    if (card.areaEffect() != null) {
      createAreaEffect(card.areaEffect(), x, y, side, cardLevel, null, "cast", name);
    }
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
      castProjectiles(card, data, king, cardLevel, side, x, y);
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
   * and the ids it has hit.
   */
  private void castProjectiles(
      DeployCard card,
      ProjectileData data,
      TowerEntity king,
      int cardLevel,
      int side,
      int x,
      int y) {
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
              delay);
        } else if (!card.spell()) {
          // A troop card's: from the point less five times the king's collision radius along the
          // length, whichever side plays, at three times it, onto the point.
          int collision = king.getData().collisionRadius();
          projectile.cast(king, cardLevel, tx, ty - 5 * collision, height, tx, ty, delay);
        } else {
          projectile.cast(king, cardLevel, kx + (vec[0] >> 2), vec[1] + ky, height, tx, ty, delay);
        }
        holder.add(projectile);
        registrationPass(projectile);
        if (chain != null) {
          projectile.joinChain(chain, rx, ry);
        }
        delay += card.projectileIntervalMs();
      }
      base += card.projectileWaveIntervalMs();
    }
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
   * carried.
   *
   * @param projectile the projectile that landed
   * @param x the impact point along the width
   * @param y the impact point along the length
   */
  public void impactAreaEffect(ProjectileEntity projectile, int x, int y) {
    AreaEffectEntity areaEffect =
        createAreaEffect(
            projectile.getData().spawnAreaEffectObject(),
            x,
            y,
            projectile.side(),
            projectile.getPackedLevel(),
            null,
            "projectile",
            projectile.name());
    for (WorldObserver observer : observers) {
      observer.projectileAreaEffect(tick, projectile, areaEffect);
    }
  }

  /**
   * The character spawn of a projectile's impact: its children in the card formation around the
   * impact point, the spread their collision radius when there are several, each created kept
   * inside the arena, at the projectile's level, deploying for the row's deploy time when it has
   * one, and registered with its registration visit; only then is it moved off the water, onto the
   * point the relocation gives its created point, which also undoes whatever its registration visit
   * moved it by.
   *
   * @param projectile the projectile that landed
   * @param x the impact point along the width
   * @param y the impact point along the length
   */
  public void impactSpawn(ProjectileEntity projectile, int x, int y) {
    ProjectileData data = projectile.getData();
    UnitData child = spawnedRow(data.spawnCharacter());
    if (child.hitpoints() <= 0
        || child.building()
        || child.spawnPathfindSpeed() != 0
        || child.onStartingAction() != null) {
      throw new UnsupportedOperationException(
          data.name()
              + "'s impact makes "
              + child.name()
              + ", without hit points, a building, pathing to its point or starting an action,"
              + " which is not modelled");
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
      int made = spawnCounts.merge(projectile.name(), 1, Integer::sum) - 1;
      CharacterEntity spawned =
          CharacterEntity.spawned(
              this,
              child,
              projectile.name() + "_" + made,
              projectile.side(),
              cx,
              cy,
              PackedLevel.level(PackedLevel.pack(projectile.getPackedLevel(), child.rarity())));
      // A fixed priority per child: the k-th is taken as (20k)^2 nearer by a selection.
      if (data.spawnConstPriority()) {
        spawned.getView().setSquaredDistanceReduction((i * 20) * (i * 20));
      }
      if (data.spawnCharacterDeployTimeMs() >= 1) {
        spawned.deployFor(data.spawnCharacterDeployTimeMs());
      }
      holder.addRegistered(spawned);
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, projectile, spawned, cx, cy);
      }
      int packed = Relocation.relocate(grid.getWidth(), grid.getHeight(), cx, cy, -1, grid::water);
      spawned.getView().setX(Relocation.unpackX(packed));
      spawned.getView().setY(Relocation.unpackY(packed));
    }
  }

  /**
   * The projectiles another one's impact launches: as many as the spawned row's spawn count, at
   * least one, in a fan. Each starts from the parent's position at the height the parent aimed at,
   * aimed beyond the parent's aim along the line it came, that line turned by the spawned row's
   * spawn radius, taken as degrees, times the projectile's step in the fan over the count - the
   * steps running from minus half the count up by one - at the parent's level, with the parent's
   * root. Each is handed to the holder, which admits it at the next cleanup, and one that flies to
   * a point runs its first pass at once, its body widened by the row's start radius, before the
   * next is made.
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
      projectile.launchSpawned(parent, vec[0] + parent.getAimX(), vec[1] + parent.getAimY());
      holder.add(projectile);
      registrationPass(projectile);
      step++;
    }
  }

  /**
   * The pass a projectile flying to a point runs over what its body covers: the spatial index's box
   * of its body radius, widened by the extra, by its body's half height - or its circle without one
   * - buildings tested as squares; one fresh hit id for the whole pass; and the travelling hit on
   * each entity found, in the index's order, until a hit finishes a projectile that stops at
   * collisions.
   *
   * @param projectile the projectile
   * @param x where the pass is centred, along the width
   * @param y where the pass is centred, along the length
   * @param extra how much the body radius is widened
   */
  public void cellPass(ProjectileEntity projectile, int x, int y, int extra) {
    ProjectileData data = projectile.getData();
    if (data.projectileRadius() < 1) {
      // Without a body the deflection pass runs, which finds nothing without deflecting areas.
      return;
    }
    List<GridEntity> found =
        index.query(
            new SpatialQuery(
                x,
                y,
                data.projectileRadius() + extra,
                data.projectileRadiusY(),
                false,
                true,
                0,
                -1));
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
   * A flying body's hit on one entity it covers, as the translated hit runs it. Held by the Log's
   * and the Barbarian Barrel's hits: the damage at the level, the id list, and no push from a hit
   * that kills; held by no run: the own side spared, the crown-tower share, the untouchable
   * listing, the air and jump checks, and the push itself. In order: none on its own side when it
   * hits enemies only, none on an entity it has hit already; an untouchable character is listed as
   * hit and spared; a character on a layer the projectile does not reach, or in the air, is spared.
   * An entity with hit points takes the projectile's damage at its level, or its crown-tower share,
   * from the direction of the pass's centre, and is listed as hit; then a character whose movement
   * is still on is pushed the row's pushback away from the projectile, the row's push-all lifting
   * the gates. A projectile that stops at collisions is finished by a hit that landed on an entity
   * with hit points left, and the pass ends; held by the Hunter's pellets.
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
    if (entity.getHitPoints() == null) {
      return false;
    }
    int damage =
        entity.getTargetView().crownTower() ? projectile.towerDamage() : projectile.damage();
    boolean standing = entity.getHitPoints().getHitPoints() >= 1;
    projectile.getHitIds().add(id);
    DamageResult result =
        dealProjectileDamage(projectile, entity, damage, hitId, view.getX() - x, view.getY() - y);
    if (data.pushback() >= 1 && entity instanceof CharacterEntity character) {
      character.pushedByTravellingHit(
          projectile.getX(), projectile.getY(), data.pushback(), data.pushbackAll());
    }
    // A projectile that stops at collisions is finished by a hit that landed on an entity that had
    // hit points left.
    if (standing && result.landed() && data.checkCollisions()) {
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
   * sequence mode that is attacking the entity has its attack reset, as an inferno's ramp is. The
   * row columns the break also reads - a pushback on the entity and an action it schedules - are
   * refused as it is created.
   */
  void shieldBroken(WorldEntity broken) {
    for (WorldEntity entity : present) {
      if (entity instanceof CharacterEntity character
          && character.getData().attackSequence().mode() != 0
          && character.isActive(CharacterEntity.TARGETING_SLOT)
          && character.getTargeting().getReference() == broken.getTargetView()
          && character.getView().getState() == GridEntityState.ATTACKING) {
        character.getTargeting().clearAttack();
      }
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
   * The death slot: what a dying object's row does as it dies, in order - its area effect at its
   * point, for its side and at its level; what its buffs leave; its death damage; its death spawn;
   * its death projectiles. A death whose row sets a column of the slot the battle does not model is
   * refused, and so is the death of one whose area object is still in the battle, which would end
   * it.
   */
  private void deathSlot(WorldEntity dying, UnitData data) {
    if (!data.unmodelledDeathColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died, and what its row does as it dies is not modelled: "
              + data.unmodelledDeathColumns());
    }
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
    deathDamage(dying, data);
    deathSpawn(dying, data);
    deathProjectiles(dying, data);
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
   * and joins the live list at the tick's closing cleanup.
   *
   * <p>Refused rather than guessed: a ring (a death spawn radius), and a child that is a building,
   * paths to its point or has a starting action. A dying row that spawns the same unit instead is
   * refused with the death columns.
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
      if (buff.deathSpawnRadius() != 0
          || child.building()
          || child.spawnPathfindSpeed() != 0
          || child.onStartingAction() != null) {
        throw new UnsupportedOperationException(
            dying.name()
                + " died carrying "
                + buff.name()
                + ", whose death spawn "
                + child.name()
                + " stands on a ring, is a building, paths to its point or starts an action,"
                + " which is not modelled");
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
    int radius = data.deathSpawnRadius();
    int count = data.deathSpawnCount();
    UnitData child = spawnedRow(data.deathSpawnCharacter());
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
            SpawnPlacement.position(
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
                (px, py) -> true);
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
                count == 1,
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
      if (DEATH_SPAWN_IMMUNE_FIRST_TICK) {
        spawned.startSpawnImmunity();
      }
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, dying, spawned, madeX, madeY);
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
    typedHits.add(new TypedHit(source, target, type, amount, directionX, directionY));
  }

  /**
   * Deals every queued typed hit, in the order they were queued: the type's pipeline, a damage id
   * from the battle's hit counter when the type takes one, the typed hit's entry, then the type's
   * action on the source and its action on the target, and the observers are told.
   */
  private void drainTypedHits() {
    List<TypedHit> due = new ArrayList<>(typedHits);
    typedHits.clear();
    for (TypedHit hit : due) {
      WorldEntity target = hit.target();
      if (target.getHitPoints() == null) {
        continue;
      }
      int amount = pipeline(hit);
      int damageId = hit.type().acquireDamageId() ? nextHitId() : 0;
      // A source that has left the battle kills as nothing does.
      WorldEntity source = hit.source() == null || hit.source().isLeft() ? null : hit.source();
      DamageResult result =
          target.takeTypedHit(amount, damageId, hit.directionX(), hit.directionY());
      // The death runs inside the hit, before the type's actions are scheduled.
      if (result.died()) {
        target.die(source);
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
   * A typed hit's amount after its type's pipeline. The level scaling reads the source's own rarity
   * row and packed level while the source is in the battle; once it has left, the level it had and
   * the Common row, and the multiplier no longer counts it as a source.
   */
  private static int pipeline(TypedHit hit) {
    WorldEntity source = hit.source();
    boolean noDamage = (hit.target().getView().getFlags() & EntityFlags.NO_DAMAGE) != 0;
    if (source == null) {
      if (hit.type().enableLevelScaling()) {
        throw new UnsupportedOperationException(
            "the level of a typed hit without a source is not established; "
                + hit.type().name()
                + " asks for it");
      }
      return hit.type().pipeline(hit.amount(), noDamage, false, null, 0);
    }
    boolean present = !source.isLeft();
    RarityTable rarity = present ? source.getData().rarity() : RarityTable.COMMON;
    return hit.type().pipeline(hit.amount(), noDamage, present, rarity, source.getPackedLevel());
  }

  /**
   * The spawner: creates the children a spawn row's block describes, each one after the other.
   *
   * <p>For each child, in order: where it stands (on the point, one unit right of it over water, or
   * on the ring), kept 250 inside the arena; its creation, for the source's side, in the lane of
   * its own position; its level, the row's or the source's, re-based on the child's own rarity;
   * walking at once as the level setter leaves it, or deploying when the row asks, for the row's
   * own deploy time when it has one; its id and its registration visit at once, inside the pass
   * that runs the spawn, over this tick's index, so a push from a unit already standing there moves
   * it; its first-tick immunity; and the action it runs as it is spawned, which starts at once when
   * it has no delay, since a pending pass is in progress. It joins the live list at the tick's
   * closing cleanup and is first visited on the next tick.
   *
   * <p>A creation that ignores effects is the same creation: the flag only skips the spawn effect,
   * which is presentation.
   *
   * <p>Refused rather than guessed: a morph, a spawn for the other side, the ring's lane mirror and
   * pushback, a ring around a character source, which reads its own spawn columns, a unit that
   * paths to its spawn point, and a unit with a starting action of its own, which a child starts as
   * it joins the live list. A child without a speed stands where it is made, and one without hit
   * points is taken like any other.
   *
   * @param source the object the children are spawned from
   * @param arguments the block the row's perform works out
   * @return the children, in the order they were made
   */
  public List<SpawnHost> spawnCharacters(SpawnHost source, SpawnArguments arguments) {
    UnitData data = arguments.configuration();
    refuseUnestablished(source, arguments, data);
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
              (x, y) -> SpawnPassable.passable(tileMap, x, y, data.collisionRadius()));
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
      if (arguments.useDeploy()) {
        child.startDeploying();
      }
      if (arguments.deployTimeMs() != 0) {
        child.deployFor(arguments.deployTimeMs());
      }
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
    }
    return made;
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
   * Hands a champion a spawn made to its side's champion controllers. The observers are told, and
   * nothing about the unit changes: no ability is modelled, so no controller acts on it.
   *
   * @param source the object the champion was spawned from
   * @param child the champion
   */
  void handOverChampion(SpawnHost source, SpawnHost child) {
    CharacterEntity champion = (CharacterEntity) child;
    for (WorldObserver observer : observers) {
      observer.championHandedOver(tick, source, champion);
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
   * String)} does, with a parent and an object it follows. A hit action that does not clone, and a
   * row that follows its parent, are refused on an area effect no action made: no other path that
   * makes one is held. Refused too: a following row that chains another area effect, which would
   * follow what it follows, or whose buff attracts, whose pull a moving area effect gates by an
   * angle.
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
    if (data.onHitAction() != null && !data.cloning() && !how.equals("action")) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " has a hit action and was not made by an action, which is not modelled");
    }
    if (data.followsParent() && !how.equals("action")) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " follows its parent and was not made by an action, which is not modelled");
    }
    if (data.followsParent()
        && (data.spawnAreaEffectObject() != null
            || data.buff() != null && buffData(data.buff()).attracts())) {
      throw new UnsupportedOperationException(
          "the area effect "
              + row
              + " follows its parent and chains an area effect or pulls, which is not modelled");
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
   * Creates the area effect an action's spawn row names: at the point of the holder's owner, for
   * the source's side and at its level, re-based on the area effect's own rarity, the source kept
   * as its parent; a row that follows its parent follows the owner. It is handed to the holder in
   * the pass that ran the action, so it is admitted at that tick's closing cleanup and first
   * updates on the next tick. The observers are told after it is created.
   *
   * <p>Refused rather than guessed: a source that is a clone, whose clone byte the area effect
   * would copy, which nothing the battle models reads.
   *
   * @param owner the owner of the holder that ran the action
   * @param action the spawn row's name
   * @param row the area effect row's name
   * @param source the entity that caused the action
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  void spawnAreaEffect(SpawnHost owner, String action, String row, SpawnHost source, int phase) {
    if (source instanceof CharacterEntity unit && unit.isClone()) {
      throw new UnsupportedOperationException(
          action + " spawns " + row + " from a clone, which is not modelled");
    }
    AreaEffectEntity areaEffect =
        createAreaEffect(
            row,
            owner.x(),
            owner.y(),
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
  }

  /** Tells the observers a taunt's perform reached a unit. */
  void tauntPerformed(
      CharacterEntity unit, String action, int phase, ActionOwner instigator, WorldEntity forced) {
    for (WorldObserver observer : observers) {
      observer.tauntPerformed(tick, unit, action, phase, instigator, forced);
    }
  }

  /** Tells the observers what a taunt's arming or step did. */
  void tauntStepped(
      CharacterEntity unit, WorldEntity forced, int durationMs, int falloffMs, List<String> calls) {
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
  void notAttackingBuff(CharacterEntity unit, int time) {
    BuffData buff = buffData(unit.getData().buffWhenNotAttacking());
    unit.getBuffs().apply(buff, time, unit.getPackedLevel(), unit, unit.side());
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
          || (entity.getView().getFlags() & EntityFlags.UNTARGETABLE) != 0
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
   * first takes a buff that deals damage.
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
    BuffData data = buffData(buff);
    for (WorldObserver observer : observers) {
      observer.buffSpawned(tick, owner, action, data, timeMs, source.packedLevel(), source);
    }
    owner.getBuffs().apply(data, timeMs, source.packedLevel(), source, source.side());
  }

  /**
   * A Clone's creator: makes a clone of a unit on the unit's point, kept inside the arena, of the
   * unit's row and side, at the Clone's level re-based on the row's rarity, named after the unit
   * and how many clones of it came before. The clone setter gives it 1 hit point of 1; it is set up
   * as a clone through its setter, which with the combat gate after it leaves its movement
   * component on and its targeting component off, faces as the unit faces, and is handed to the
   * holder with its registration visit - in the clone state with no route, which moves nothing -
   * and joins the live list at the tick's closing cleanup. It takes a copy of every buff the unit
   * carries, the Clone's own buff, just put on the unit, among them, with the time each has left.
   * Then the two move apart: the clone back toward its own side, and the unit, set up as a clone
   * too unless it dashes, forward.
   *
   * @param original the unit
   * @param instigator the Clone's area effect
   * @param action the Clone's action
   */
  void makeClone(CharacterEntity original, AreaEffectEntity instigator, Clone action) {
    UnitData row = original.getData();
    GridEntity at = original.getView();
    int fromX = at.getX();
    int fromY = at.getY();
    int made = cloneCounts.merge(original.name(), 1, Integer::sum) - 1;
    CharacterEntity clone =
        CharacterEntity.spawned(
            this,
            row,
            original.name() + "_clone" + made,
            original.side(),
            inset(fromX, tileMap.width()),
            inset(fromY, tileMap.height()),
            PackedLevel.level(PackedLevel.pack(instigator.packedLevel(), row.rarity())));
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
  }

  /**
   * A clone's spawn is a clone: the clone setter on the child, the spawner as the unit it stands
   * for. A clone that deploys is refused: it would be out of collisions while it does, which is not
   * modelled.
   */
  private static void cloneSpawn(WorldEntity spawner, CharacterEntity child) {
    if (!(spawner instanceof CharacterEntity source) || !source.isClone()) {
      return;
    }
    child.markClone(spawner);
    if (child.getView().getState() == GridEntityState.DEPLOYING
        || child.getView().getDeployCountdown() > 0) {
      throw new UnsupportedOperationException(
          child.name() + " is a clone that deploys, which is not modelled");
    }
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
    return actions.tagMask("FORCE_IS_GROUND");
  }

  /** The bit of FORCE_IS_AIR in an entity's tag word, as the data numbers the game tags. */
  long forceIsAir() {
    return actions.tagMask("FORCE_IS_AIR");
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

  void buffApplied(WorldEntity target, BuffInstance buff) {
    for (WorldObserver observer : observers) {
      observer.buffApplied(tick, target, buff);
    }
  }

  void buffRefreshed(WorldEntity target, BuffInstance buff, int before, SpawnHost source) {
    for (WorldObserver observer : observers) {
      observer.buffRefreshed(tick, target, buff, before, source);
    }
  }

  void buffRemoved(WorldEntity target, BuffInstance buff) {
    for (WorldObserver observer : observers) {
      observer.buffRemoved(tick, target, buff);
    }
  }

  /**
   * Deals one hit of a buff's damage over time, tells the observers, and runs the death it causes,
   * with nothing as what killed it.
   */
  void dealBuffDamage(WorldEntity target, BuffInstance buff, int damage) {
    int before = target.getHitPoints().getHitPoints();
    // The damage of a buff comes from its source: an area effect, which is never struck back, or a
    // character, whose buff on a reflecting unit is not modelled.
    SpawnHost source = buff.getSource();
    if (target.getData().reflectedAttackBuff() != null && source instanceof WorldEntity) {
      throw new UnsupportedOperationException(
          target.name() + " reflects and takes a buff's damage from a character, not modelled");
    }
    DamageResult result = target.takeDamageOverTime(damage);
    reflect(target, (BattleEntity) source, before, result, 0, 0);
    for (WorldObserver observer : observers) {
      observer.buffDamaged(tick, target, buff, damage, before, result);
    }
    if (result.died()) {
      // The killing side is the one the buff was applied for.
      target.die(null, buff.getSide());
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
    DamageResult result = victim.takeDamage(damage, 0, 0, 0, areaEffect.getData().affectsHidden());
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
    } else if (arguments.constPriority()) {
      refused = "the ring's lane mirror and fixed priority";
    } else if (arguments.radius() != 0 && arguments.spawnPushback()) {
      refused = "the pushback of a ring";
    } else if (arguments.radius() != 0 && source.isCharacter()) {
      refused = "a ring around a character, which reads the character's own spawn columns";
    } else if (data.spawnPathfindSpeed() != 0) {
      refused = "a unit that paths to its spawn point";
    } else if (data.onStartingAction() != null) {
      refused = "a child with a starting action, started as it joins the live list";
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
    // The typed hits land after every post-hook and before phase 3; the observers see them landed.
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
  }
}
