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
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleRandom;
import org.crforge.core.battle.EntityHolder;
import org.crforge.core.battle.HolderPasses;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.DamageType;
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
import org.crforge.core.battle.projectile.ProjectileChain;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
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
            + " golem_death_pushback; a single child on the dying object's point and a bomb's"
            + " death slot as its deploy ends, without the death hooks, held by"
            + " giant_skeleton_bomb; several death spawn children in front of the dying object,"
            + " a lifetime's death without the death handler and a building spawner's children in"
            + " front of it, held by tombstone_life and goblin_hut_life; an area effect created by"
            + " a death or placed directly and its hits dealt, held by area_effect_direct and"
            + " area_effect_death. Refused: a death whose row sets a column of the slot not"
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
   * Whether a character in the battle names a buff in its IgnoreTargetsWithBuff column, so that its
   * targeting would read who carries it.
   *
   * @param buff the buff row's name
   */
  boolean passedOverBySomeone(String buff) {
    for (WorldEntity entity : present()) {
      if (buff.equals(entity.getData().ignoreTargetsWithBuff())) {
        return true;
      }
    }
    for (BattleEntity entity : holder.queued()) {
      if (entity instanceof WorldEntity w && buff.equals(w.getData().ignoreTargetsWithBuff())) {
        return true;
      }
    }
    return false;
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
    DamageResult result = entity.takeDamage(damage, 0, directionX, directionY);
    for (WorldObserver observer : observers) {
      observer.damageDealt(tick, entity, damage, result);
    }
    if (result.died()) {
      entity.die(attacker);
    }
    return result;
  }

  /**
   * Hands a launched projectile to the holder, which gives it its id at once and admits it at the
   * next cleanup, and tells every observer of the launch.
   */
  public void launch(ProjectileEntity projectile) {
    holder.add(projectile);
    for (WorldObserver observer : observers) {
      observer.projectileLaunched(tick, projectile);
    }
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
    DamageResult result = victim.takeDamage(damage, 0, 0, 0);
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
    DamageResult result = target.takeDamage(damage, 0, directionX, directionY);
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

  /** The king tower of a side still in the battle, or null. */
  public TowerEntity kingTower(int side) {
    for (WorldEntity entity : known.values()) {
      if (entity instanceof TowerEntity tower && tower.getData().king() && tower.side() == side) {
        return tower;
      }
    }
    return null;
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
   * @param dying the entity that died
   * @param attacker what killed it: an arena entity, a projectile, or null for nothing
   */
  void entityDied(WorldEntity dying, BattleEntity attacker) {
    UnitData data = dying.getData();
    deathSlot(dying, data);
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
   * once when it has a speed, with no deploy and no first-tick immunity, registered inside the
   * post-hook pass with its registration visit, and joining the live list at the tick's closing
   * cleanup.
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
    UnitData child = records.unit(data.spawnCharacter());
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
      holder.addRegistered(spawned);
      for (WorldObserver observer : observers) {
        observer.characterSpawned(tick, spawner, spawned, x, y);
      }
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
    UnitData child = records.unit(data.spawnCharacter());
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
   * the holder, whose opening cleanup of the same step admits them.
   *
   * @param card the spell card
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
        } else {
          projectile.cast(king, cardLevel, kx + (vec[0] >> 2), vec[1] + ky, height, tx, ty, delay);
        }
        holder.add(projectile);
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
    UnitData child = records.unit(data.spawnCharacter());
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
   * The projectile another one's impact launches: from the parent's position at the height the
   * parent aimed at, aimed beyond the parent's aim along the line it came, at the parent's level,
   * with the parent's root. It is handed to the holder, which admits it at the next cleanup, and a
   * projectile that flies to a point runs its first pass at once, its body widened by the row's
   * start radius: a pass no run finds anyone in.
   *
   * @param parent the projectile that landed
   * @param hx the point beyond the parent's aim, along the width
   * @param hy the point beyond the parent's aim, along the length
   */
  public void impactProjectile(ProjectileEntity parent, int hx, int hy) {
    ProjectileData data = records.projectile(parent.getData().spawnProjectile());
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          parent.getData().name()
              + " spawns "
              + data.name()
              + ", which sets columns its flight does not model: "
              + data.unmodelledColumns());
    }
    ProjectileEntity projectile = new ProjectileEntity(this, data, parent.side());
    projectile.launchSpawned(parent, hx, hy);
    holder.add(projectile);
    if (data.homingLike()) {
      cellPass(projectile, projectile.getX(), projectile.getY(), data.projectileStartExtraRadius());
    }
  }

  /**
   * The pass a projectile flying to a point runs over what its body covers: the spatial index's box
   * of its body radius, widened by the extra, by its body's half height - or its circle without one
   * - buildings tested as squares; one fresh hit id for the whole pass; and the travelling hit on
   * each entity found, in the index's order.
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
      if (entity != null) {
        travellingHit(projectile, entity, x, y, hitId);
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
   * the gates.
   */
  private void travellingHit(
      ProjectileEntity projectile, WorldEntity entity, int x, int y, int hitId) {
    ProjectileData data = projectile.getData();
    if (data.onlyEnemies() && (entity.side() & 1) == (projectile.side() & 1)) {
      return;
    }
    int id = entity.getId();
    if (projectile.getHitIds().contains(id)) {
      return;
    }
    GridEntity view = entity.getView();
    if (entity.untouchable()) {
      projectile.getHitIds().add(id);
      return;
    }
    if (!data.aoeToAir() && view.isAir()) {
      return;
    }
    if (!data.aoeToGround() && !view.isAir()) {
      return;
    }
    if (!data.aoeToAir() && view.getState() == GridEntityState.JUMPING) {
      return;
    }
    if (entity.getHitPoints() == null) {
      return;
    }
    int damage =
        entity.getTargetView().crownTower() ? projectile.towerDamage() : projectile.damage();
    projectile.getHitIds().add(id);
    dealProjectileDamage(projectile, entity, damage, hitId, view.getX() - x, view.getY() - y);
    if (data.pushback() >= 1 && entity instanceof CharacterEntity character) {
      character.pushedByTravellingHit(
          projectile.getX(), projectile.getY(), data.pushback(), data.pushbackAll());
    }
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
   * point, for its side and at its level; its death damage; its death spawn. A death whose row sets
   * a column of the slot the battle does not model is refused.
   */
  private void deathSlot(WorldEntity dying, UnitData data) {
    if (!data.unmodelledDeathColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          dying.name()
              + " died, and what its row does as it dies is not modelled: "
              + data.unmodelledDeathColumns());
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
    deathDamage(dying, data);
    deathSpawn(dying, data);
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
   * back to its ring point. With no radius a single child stands on the dying object, and several
   * stand together in front of it, the dying object's collision radius and the child's away toward
   * the enemy, the first quarter turn of that offset the in-front test accepts; where it accepts
   * none, the children stand one unit right of the dying object.
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
    UnitData child = records.unit(data.deathSpawnCharacter());
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
      if (child.hitpoints() <= 0 && child.deployTimeMs() >= 1) {
        spawned.startDeploying();
      }
      if (data.deathSpawnDeployTimeMs() > 0) {
        spawned.deployFor(data.deathSpawnDeployTimeMs());
      }
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
        observer.damageDealt(tick, target, amount, result);
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
   * <p>Refused rather than guessed: a row that sets a column the area effect does not model.
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
    AreaEffectData data = records.areaEffect(row);
    if (!data.unmodelledColumns().isEmpty()) {
      throw new UnsupportedOperationException(
          "the area effect " + row + " sets columns not modelled: " + data.unmodelledColumns());
    }
    if (data.buff() != null) {
      buffData(data.buff());
    }
    AreaEffectEntity areaEffect =
        new AreaEffectEntity(this, data, side, x, y, PackedLevel.pack(packedLevel, data.rarity()));
    holder.add(areaEffect);
    areaEffect.setName(name != null ? name : row + "_" + areaEffect.getId());
    for (WorldObserver observer : observers) {
      observer.areaEffectCreated(tick, areaEffect, how, source);
    }
    return areaEffect;
  }

  /** A buff's row, refused when it sets a column the battle does not model. */
  private BuffData buffData(String name) {
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
   * takes a buff that deals damage. Each is applied with the area effect as the source, at its
   * level and for its side, once every target has been found.
   *
   * @param areaEffect the area effect
   * @param radius the radius of its hit
   * @param time how long the buff lasts
   */
  void areaBuff(AreaEffectEntity areaEffect, int radius, int time) {
    BuffData buff = buffData(areaEffect.getData().buff());
    boolean damaging = BuffComponent.damagePerSecond(buff, 0) > 0;
    boolean towerTaken = false;
    List<WorldEntity> targets = new ArrayList<>();
    for (WorldEntity entity : present) {
      if (!ShapeTests.withinCircleShape(
              entity.getView(), areaEffect.getX(), areaEffect.getY(), radius)
          || !areaEffect.buffReaches(entity)) {
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
    for (WorldObserver observer : observers) {
      observer.areaBuff(tick, areaEffect, buff, time, targets);
    }
    for (WorldEntity target : targets) {
      target
          .getBuffs()
          .apply(buff, time, areaEffect.getPackedLevel(), areaEffect, areaEffect.side());
    }
  }

  /** The name of the next buff instance listed in the battle. */
  String nextBuffKey() {
    return "buff_" + ++buffKeys;
  }

  void buffApplied(WorldEntity target, BuffInstance buff) {
    for (WorldObserver observer : observers) {
      observer.buffApplied(tick, target, buff);
    }
  }

  void buffRefreshed(WorldEntity target, BuffInstance buff, int before) {
    for (WorldObserver observer : observers) {
      observer.buffRefreshed(tick, target, buff, before);
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
    DamageResult result = target.takeDamageOverTime(damage);
    for (WorldObserver observer : observers) {
      observer.buffDamaged(tick, target, buff, damage, before, result);
    }
    if (result.died()) {
      target.die(null);
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
    DamageResult result = victim.takeDamage(damage, 0, 0, 0);
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

  @Override
  public void postPass(int tick) {
    grid.swap();
    index.clear();
  }
}
