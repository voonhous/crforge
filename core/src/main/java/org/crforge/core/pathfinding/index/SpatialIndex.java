package org.crforge.core.pathfinding.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;

/**
 * The per-tick bucket index that answers "which entities are near this point".
 *
 * <p>The arena is covered by square buckets of 1024 game units, sized from the arena's cell counts
 * as {@code (cells * 500 + 1023) / 1024} each way, which gives 18 by 32 buckets for the standard
 * arena. Bucket {@code (cx, cy)} is entry {@code width * cy + cx}.
 *
 * <p>An entity joins every bucket its collision square touches; a moving entity's square is widened
 * by half a cell (250 units) so a query still finds it after it has stepped, and an entity with a
 * collision radius below one is not indexed at all. The index is rebuilt from the entity list in
 * creation order at the start of every tick, before any component pass runs, and cleared at the end
 * of the tick. Bucket membership is therefore fixed for the whole tick while the geometric tests
 * read live positions.
 *
 * <p>Results come from a pool of ten lists. A query takes one and answers null when the pool is
 * empty; the caller returns the list with {@link #release(List)} when it is done with it.
 *
 * <p>The class is not thread safe.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Bucket layout, insertion margin, bucket visiting order, the seen mark, the"
            + " type mask, the team rule and both kings-last orderings agree with the"
            + " reference line for line; the five reference walks hold the circle query, the"
            + " multi-unit smoke scenarios hold it with several movers.")
public final class SpatialIndex {

  /** Bucket edge length in game units; the bucket of a coordinate is that coordinate shifted. */
  public static final int BUCKET_UNITS = 1024;

  /** Shift that turns a game-unit coordinate into a bucket coordinate. */
  public static final int BUCKET_SHIFT = 10;

  /** Number of result lists a query may hold at once. */
  public static final int RESULT_LIST_POOL_SIZE = 10;

  /** Extra reach, in game units, given to an entity whose movement component is active. */
  public static final int MOVING_ENTITY_MARGIN = 250;

  /** Side value that stands for a neutral entity, which forms a team of its own. */
  public static final int NEUTRAL_SIDE = 100;

  /** Size of the bucket grid covering the arena. */
  public record Dimensions(int wide, int high) {}

  private final int width;
  private final int high;
  private final List<List<GridEntity>> buckets;

  /** Number of result lists still available to a query. */
  private int freeResultLists = RESULT_LIST_POOL_SIZE;

  /**
   * True once at least one entity with a collision radius has been inserted since the last clear.
   */
  private boolean populated;

  /** Creates an empty index covering an arena of the given size in routing cells. */
  public SpatialIndex(int cellsWide, int cellsHigh) {
    Dimensions dimensions = dimensions(cellsWide, cellsHigh);
    this.width = dimensions.wide();
    this.high = dimensions.high();
    this.buckets = new ArrayList<>(width * high);
    for (int i = 0; i < width * high; i++) {
      buckets.add(new ArrayList<>());
    }
  }

  /**
   * Bucket counts for an arena of the given size in routing cells: the arena's extent in game units
   * rounded up to whole buckets.
   */
  public static Dimensions dimensions(int cellsWide, int cellsHigh) {
    return new Dimensions(
        (cellsWide * 500 + BUCKET_UNITS - 1) >> BUCKET_SHIFT,
        (cellsHigh * 500 + BUCKET_UNITS - 1) >> BUCKET_SHIFT);
  }

  /**
   * Team of an entity as every team comparison in the targeting code computes it: a neutral entity
   * forms team 2, everything else belongs to team {@code side & 1}.
   */
  public static int team(GridEntity entity) {
    return entity.getSide() == NEUTRAL_SIDE ? 2 : entity.getSide() & 1;
  }

  /** Number of buckets across the arena's width. */
  public int getWidth() {
    return width;
  }

  /** Number of buckets along the arena's length. */
  public int getHigh() {
    return high;
  }

  /** True once an entity with a collision radius has been inserted since the last clear. */
  public boolean isPopulated() {
    return populated;
  }

  /**
   * Puts one entity into every bucket its collision square touches. An entity with a collision
   * radius below one is skipped; a moving entity's square is widened by {@link
   * #MOVING_ENTITY_MARGIN}.
   */
  public void insert(GridEntity entity) {
    int radius = entity.getCollisionRadius();
    if (radius < 1) {
      return;
    }
    int margin = entity.isMovementActive() ? radius + MOVING_ENTITY_MARGIN : radius;
    int xLow = (entity.getX() - margin) >> BUCKET_SHIFT;
    int xHigh = (entity.getX() + margin) >> BUCKET_SHIFT;
    int yLow = (entity.getY() - margin) >> BUCKET_SHIFT;
    int yHigh = (entity.getY() + margin) >> BUCKET_SHIFT;
    if (xLow <= xHigh && yLow <= yHigh) {
      for (int cx = xLow; cx <= xHigh; cx++) {
        if (cx < 0 || cx >= width) {
          continue;
        }
        for (int cy = yLow; cy <= yHigh; cy++) {
          if (cy < 0 || cy >= high) {
            continue;
          }
          buckets.get(width * cy + cx).add(entity);
        }
      }
    }
    populated = true;
  }

  /** Empties every bucket. */
  public void clear() {
    for (List<GridEntity> bucket : buckets) {
      bucket.clear();
    }
    populated = false;
  }

  /** Empties every bucket and inserts the given entities in the order they are listed. */
  public void rebuild(List<GridEntity> entities) {
    clear();
    for (GridEntity entity : entities) {
      insert(entity);
    }
  }

  /** Returns a result list to the pool so a later query can use it. */
  public void release(List<GridEntity> result) {
    if (result != null) {
      freeResultLists++;
    }
  }

  /**
   * Answers the entities accepted by the given query, in bucket order with x outer and y inner and
   * each bucket in insertion order, each entity at most once. King towers are moved to the end when
   * the query asks for it. Answers null when no result list is free.
   */
  public List<GridEntity> query(SpatialQuery query) {
    if (freeResultLists <= 0) {
      return null;
    }
    freeResultLists--;
    List<GridEntity> result = new ArrayList<>();
    int xLow = (query.x() - query.radius()) >> BUCKET_SHIFT;
    int xHigh = (query.x() + query.radius()) >> BUCKET_SHIFT;
    int yLow = (query.y() - query.radius()) >> BUCKET_SHIFT;
    int yHigh = (query.y() + query.radius()) >> BUCKET_SHIFT;
    if (xLow > xHigh || yLow > yHigh) {
      return result;
    }
    Set<GridEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int cx = xLow; cx <= xHigh; cx++) {
      if (cx < 0 || cx >= width) {
        continue;
      }
      for (int cy = yLow; cy <= yHigh; cy++) {
        if (cy < 0 || cy >= high) {
          continue;
        }
        for (GridEntity entity : buckets.get(width * cy + cx)) {
          // Only an accepted entity is marked, so a rejected one is tested again in its next
          // bucket; the tests are position based, so the answer does not change.
          if (seen.contains(entity)) {
            continue;
          }
          if (query.typeMask() >= 1 && ((query.typeMask() >>> entity.getType()) & 1) == 0) {
            continue;
          }
          if (query.excludeTeam() != -1 && team(entity) == query.excludeTeam()) {
            continue;
          }
          if (!accepts(entity, query)) {
            continue;
          }
          result.add(entity);
          seen.add(entity);
        }
      }
    }
    if (!result.isEmpty() && query.kingsLast()) {
      moveKingsLast(result);
    }
    return result;
  }

  /**
   * Answers every indexed entity once, bucket by bucket with x outer and y inner. When {@code
   * kingsLast} is set the entities that are not king towers come first, then the king towers, each
   * group keeping its bucket order. Answers null when no result list is free.
   */
  public List<GridEntity> listQuery(boolean kingsLast) {
    if (freeResultLists <= 0) {
      return null;
    }
    freeResultLists--;
    List<GridEntity> result = new ArrayList<>();
    Set<GridEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    if (width >= 1 && high >= 1) {
      for (int cx = 0; cx < width; cx++) {
        for (int cy = 0; cy < high; cy++) {
          for (GridEntity entity : buckets.get(width * cy + cx)) {
            if (seen.add(entity)) {
              result.add(entity);
            }
          }
        }
      }
    }
    if (kingsLast) {
      List<GridEntity> partitioned = new ArrayList<>(result.size());
      for (GridEntity entity : result) {
        if (!entity.isKing()) {
          partitioned.add(entity);
        }
      }
      for (GridEntity entity : result) {
        if (entity.isKing()) {
          partitioned.add(entity);
        }
      }
      return partitioned;
    }
    return result;
  }

  /** The geometric test selected by the query's half height and building-aware flag. */
  private static boolean accepts(GridEntity entity, SpatialQuery query) {
    if (query.halfHeight() != 0) {
      if (query.buildingAware() && entity.isBuilding()) {
        return ShapeTests.boxOverlap(
            entity, query.x(), query.y(), query.radius(), query.halfHeight());
      }
      return ShapeTests.withinBox(
          entity,
          query.x() - query.radius(),
          query.y() - query.halfHeight(),
          2 * query.radius(),
          2 * query.halfHeight());
    }
    if (query.buildingAware()) {
      return ShapeTests.withinCircleShape(entity, query.x(), query.y(), query.radius());
    }
    return ShapeTests.withinCircle(entity, query.x(), query.y(), query.radius());
  }

  /**
   * Moves the king towers to the end of the result. The list is scanned from its last entry down to
   * its first, so a king tower found nearer the front ends up after one found nearer the back.
   */
  private static void moveKingsLast(List<GridEntity> result) {
    for (int i = result.size() - 1; i >= 0; i--) {
      GridEntity entity = result.get(i);
      if (entity.isKing()) {
        result.remove(i);
        result.add(entity);
      }
    }
  }
}
