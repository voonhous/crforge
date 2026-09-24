package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.EntityHolder;
import org.crforge.core.battle.HolderPasses;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridUnitState;
import org.crforge.core.pathfinding.IndexNeighbourQuery;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.grid.CellCosts;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.FootprintOverlay;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.move.MovementGlobals;
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
 * visits begin and end, about every hit that lands, and about every projectile launched and
 * arrived.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the index and the overlay are rebuilt in the pre-pass from the id-ordered"
            + " snapshot of the arena entities and retired in the post-pass, the overlay's per-side"
            + " change flags are copied once per tick, a projectile is in neither and is handed to"
            + " the holder in the tick of its launch, and a dead entity leaves the holder in the"
            + " closing cleanup of the tick it dies, when every character is told at once and its"
            + " default target lists lose it. Not modelled: the game mode's own per-tick work"
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

  /** Those watching the arena from outside the tick, in the order they were added. */
  private final List<WorldObserver> observers = new ArrayList<>();

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

  /** This tick's arena entities in ascending id. */
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
      if (entity instanceof CharacterEntity character) {
        character.registerCandidates(present);
      }
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
   * The side lists' part of a removal: a removed entity leaves every character's default targets in
   * the same cleanup, after each character's own notice has run.
   */
  @Override
  public void entityRemoved(BattleEntity removed) {
    if (!(removed instanceof WorldEntity gone)) {
      return;
    }
    known.remove(gone.getView());
    for (WorldEntity entity : known.values()) {
      if (entity instanceof CharacterEntity character) {
        character.forget(gone.getView());
      }
    }
  }

  @Override
  public void afterPostHooks() {
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
