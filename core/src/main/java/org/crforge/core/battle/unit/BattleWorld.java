package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
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
import org.crforge.core.battle.expression.Expression;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
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
import org.crforge.core.pathfinding.GridUnitState;
import org.crforge.core.pathfinding.IndexNeighbourQuery;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.grid.CellCosts;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.FootprintOverlay;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.move.MovementGlobals;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.NeighbourQuery;
import org.crforge.core.pathfinding.target.TargetView;

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
            + " when every arena entity is told at once and its default target lists lose it. Not modelled: the game mode's own per-tick work"
            + " beside the index and the overlay.")
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
   * evaluated afresh each time, the battle's variable keys, and its tag word.
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
   * Deals the damage of one hit to the entity behind a target view, and tells every observer what
   * it did. An entity that has left the battle takes nothing.
   *
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealDamage(TargetView target, int damage, int directionX, int directionY) {
    WorldEntity entity = known.get(target.getEntity());
    if (entity == null) {
      return DamageResult.NOTHING;
    }
    DamageResult result = entity.takeDamage(damage, 0, directionX, directionY);
    for (WorldObserver observer : observers) {
      observer.damageDealt(tick, entity, damage, result);
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
      DamageResult result =
          target.takeTypedHit(amount, damageId, hit.directionX(), hit.directionY());
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
   * <p>Refused rather than guessed: a morph, a spawn for the other side, the ring's lane mirror and
   * pushback, a ring around a character source, which reads its own spawn columns, a unit that
   * paths to its spawn point, a unit without hit points, and a creation that ignores effects.
   *
   * @param source the object the children are spawned from
   * @param arguments the block the row's perform works out
   * @return how many children were spawned
   */
  public int spawnCharacters(SpawnHost source, SpawnArguments arguments) {
    UnitData data = arguments.configuration();
    refuseUnestablished(source, arguments, data);
    int made = 0;
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
      made++;
    }
    return made;
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
    } else if (arguments.ignoreEffects()) {
      refused = "a creation that ignores effects";
    } else if (arguments.radius() != 0 && arguments.spawnPushback()) {
      refused = "the pushback of a ring";
    } else if (arguments.radius() != 0 && source.isCharacter()) {
      refused = "a ring around a character, which reads the character's own spawn columns";
    } else if (data.spawnPathfindSpeed() != 0) {
      refused = "a unit that paths to its spawn point";
    } else if (data.hitpoints() <= 0) {
      refused = "a unit without hit points";
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
