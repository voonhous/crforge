package org.crforge.core.pathfinding.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.math.FixedMath;

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
        "Bucket layout, insertion margin, bucket visiting order, the seen mark, the type mask,"
            + " the team rule and both kings-last orderings agree with the reference line for"
            + " line; the walks of the reference battles hold the circle query, with several"
            + " movers at once in the random battles. The box query of a shaped area effect, a"
            + " building by its square and anything else by its circle after the filter, is held"
            + " by the reference battle evo_babydragon_vs_musketeer and SpatialIndexTest.")
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

  /** The buckets, entry {@code width * cy + cx}, each in insertion order. */
  private final ArrayList<GridEntity>[] buckets;

  /**
   * The entries of the buckets that hold an entity, in the order they were first filled, so a clear
   * empties only those: {@code filledCount} of them are in use.
   */
  private final int[] filled;

  private int filledCount;

  /**
   * One word per bucket column: bit {@code cy} is set while bucket {@code (cx, cy)} holds an
   * entity, so a query visits only the buckets that do, in the same ascending order.
   */
  private final long[] occupied;

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
    if (high > Long.SIZE) {
      throw new IllegalArgumentException(
          "an arena of " + high + " bucket rows; the index keeps at most " + Long.SIZE);
    }
    this.buckets = newBuckets(width * high);
    this.filled = new int[width * high];
    this.occupied = new long[width];
  }

  @SuppressWarnings("unchecked")
  private static ArrayList<GridEntity>[] newBuckets(int count) {
    ArrayList<GridEntity>[] buckets = (ArrayList<GridEntity>[]) new ArrayList<?>[count];
    for (int i = 0; i < count; i++) {
      buckets[i] = new ArrayList<>();
    }
    return buckets;
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
          int entry = width * cy + cx;
          ArrayList<GridEntity> bucket = buckets[entry];
          if (bucket.isEmpty()) {
            filled[filledCount++] = entry;
            occupied[cx] |= 1L << cy;
          }
          bucket.add(entity);
        }
      }
    }
    populated = true;
  }

  /** Empties every bucket: only those an insert filled hold anything. */
  public void clear() {
    for (int i = 0; i < filledCount; i++) {
      buckets[filled[i]].clear();
      occupied[filled[i] % width] = 0;
    }
    filledCount = 0;
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
   * each bucket in insertion order, each entity at most once. Crown towers are moved to the end
   * when the query asks for it. Answers null when no result list is free.
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
    long rowRange = rowRange(yLow, yHigh);
    for (int cx = Math.max(xLow, 0), lastCx = Math.min(xHigh, width - 1); cx <= lastCx; cx++) {
      // The column's buckets in range that hold an entity, by ascending row.
      for (long rows = occupied[cx] & rowRange; rows != 0; rows &= rows - 1) {
        int cy = Long.numberOfTrailingZeros(rows);
        ArrayList<GridEntity> bucket = buckets[width * cy + cx];
        for (int i = 0, size = bucket.size(); i < size; i++) {
          GridEntity entity = bucket.get(i);
          // Only an accepted entity is marked, so a rejected one is tested again in its next
          // bucket; the tests are position based, so the answer does not change. The accepted
          // entities are the result's, so the mark is a look through the result.
          if (holds(result, entity)) {
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
        }
      }
    }
    if (!result.isEmpty() && query.kingsLast()) {
      moveKingsLast(result);
    }
    return result;
  }

  /**
   * Answers the entities a segment query accepts: the buckets over the segment's box widened by the
   * width each way, x outer and y inner, each bucket in insertion order. Every entity is visited
   * once, whether accepted or not, and accepted by the test, which the caller gives: the filter and
   * the segment test on its live position. Answers null when no result list is free.
   *
   * @param ax the segment's start along the width
   * @param ay the segment's start along the length
   * @param bx the segment's end along the width
   * @param by the segment's end along the length
   * @param width how far either side of the segment the box reaches
   * @param accepts whether an entity is answered
   */
  public List<GridEntity> segmentQuery(
      int ax, int ay, int bx, int by, int width, Predicate<GridEntity> accepts) {
    if (freeResultLists <= 0) {
      return null;
    }
    freeResultLists--;
    List<GridEntity> result = new ArrayList<>();
    int xLow = (Math.min(ax, bx) - width) >> BUCKET_SHIFT;
    int xHigh = (Math.max(ax, bx) + width) >> BUCKET_SHIFT;
    int yLow = (Math.min(ay, by) - width) >> BUCKET_SHIFT;
    int yHigh = (Math.max(ay, by) + width) >> BUCKET_SHIFT;
    if (xLow > xHigh || yLow > yHigh) {
      return result;
    }
    Set<GridEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    long rowRange = rowRange(yLow, yHigh);
    for (int cx = Math.max(xLow, 0), lastCx = Math.min(xHigh, this.width - 1); cx <= lastCx; cx++) {
      // The column's buckets in range that hold an entity, by ascending row.
      for (long rows = occupied[cx] & rowRange; rows != 0; rows &= rows - 1) {
        int cy = Long.numberOfTrailingZeros(rows);
        for (GridEntity entity : buckets[this.width * cy + cx]) {
          // Unlike the point queries, a rejected entity is marked too and never tested again.
          if (!seen.add(entity)) {
            continue;
          }
          if (accepts.test(entity)) {
            result.add(entity);
          }
        }
      }
    }
    return result;
  }

  /**
   * Answers the entities a centre query accepts: the buckets over the circle, x outer and y inner,
   * each bucket in insertion order, each entity at most once. An entity is tested by the filter
   * first, then accepted when its centre, where it stands now, lies strictly within the radius: its
   * squared distance from the point, saturating, below the radius squared. No collision radius is
   * added and a building is tested like anything else. Only an accepted entity is marked, so a
   * rejected one is tested again in its next bucket. Answers null when no result list is free.
   *
   * @param x the circle's centre along the width
   * @param y the circle's centre along the length
   * @param radius the circle's radius
   * @param passes the filter
   */
  public List<GridEntity> centreQuery(int x, int y, int radius, Predicate<GridEntity> passes) {
    if (freeResultLists <= 0) {
      return null;
    }
    freeResultLists--;
    List<GridEntity> result = new ArrayList<>();
    int xLow = (x - radius) >> BUCKET_SHIFT;
    int xHigh = (x + radius) >> BUCKET_SHIFT;
    if (xLow > xHigh) {
      return result;
    }
    int yLow = (y - radius) >> BUCKET_SHIFT;
    int yHigh = (y + radius) >> BUCKET_SHIFT;
    if (yLow > yHigh) {
      return result;
    }
    int reach = radius * radius;
    long rowRange = rowRange(yLow, yHigh);
    for (int cx = Math.max(xLow, 0), lastCx = Math.min(xHigh, width - 1); cx <= lastCx; cx++) {
      // The column's buckets in range that hold an entity, by ascending row.
      for (long rows = occupied[cx] & rowRange; rows != 0; rows &= rows - 1) {
        int cy = Long.numberOfTrailingZeros(rows);
        ArrayList<GridEntity> bucket = buckets[width * cy + cx];
        for (int i = 0, size = bucket.size(); i < size; i++) {
          GridEntity entity = bucket.get(i);
          // The accepted entities, the marked ones, are the result's.
          if (holds(result, entity) || !passes.test(entity)) {
            continue;
          }
          if (FixedMath.squaredDistance(entity.getX(), entity.getY(), x, y) < reach) {
            result.add(entity);
          }
        }
      }
    }
    return result;
  }

  /**
   * Answers the entities a rectangle about a point reaches that pass a filter: the buckets over the
   * rectangle, x outer and y inner, each bucket in insertion order, each entity at most once. An
   * entity is tested by the filter first, then by its geometry on its live position: a building by
   * its square overlapping the rectangle, anything else by its circle meeting it. Only an accepted
   * entity is marked, so a rejected one is tested again in its next bucket. The answer is a list of
   * the caller's, not one of the pool's.
   *
   * @param x the rectangle's centre along the width
   * @param y the rectangle's centre along the length
   * @param halfWidth half the rectangle's width
   * @param halfHeight half its height
   * @param passes the filter
   */
  public List<GridEntity> boxQuery(
      int x, int y, int halfWidth, int halfHeight, Predicate<GridEntity> passes) {
    List<GridEntity> result = new ArrayList<>();
    int xLow = (x - halfWidth) >> BUCKET_SHIFT;
    int xHigh = (x + halfWidth) >> BUCKET_SHIFT;
    if (xLow > xHigh) {
      return result;
    }
    int yLow = (y - halfHeight) >> BUCKET_SHIFT;
    int yHigh = (y + halfHeight) >> BUCKET_SHIFT;
    if (yLow > yHigh) {
      return result;
    }
    long rowRange = rowRange(yLow, yHigh);
    for (int cx = Math.max(xLow, 0), lastCx = Math.min(xHigh, width - 1); cx <= lastCx; cx++) {
      // The column's buckets in range that hold an entity, by ascending row.
      for (long rows = occupied[cx] & rowRange; rows != 0; rows &= rows - 1) {
        int cy = Long.numberOfTrailingZeros(rows);
        ArrayList<GridEntity> bucket = buckets[width * cy + cx];
        for (int i = 0, size = bucket.size(); i < size; i++) {
          GridEntity entity = bucket.get(i);
          // The accepted entities, the marked ones, are the result's.
          if (holds(result, entity) || !passes.test(entity)) {
            continue;
          }
          boolean inside =
              entity.isBuilding()
                  ? ShapeTests.boxOverlap(entity, x, y, halfWidth, halfHeight)
                  : ShapeTests.withinBox(
                      entity, x - halfWidth, y - halfHeight, 2 * halfWidth, 2 * halfHeight);
          if (inside) {
            result.add(entity);
          }
        }
      }
    }
    return result;
  }

  /**
   * Answers every indexed entity once, bucket by bucket with x outer and y inner. When {@code
   * kingsLast} is set the entities that are not crown towers come first, then the crown towers,
   * each group keeping its bucket order. Answers null when no result list is free.
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
          for (GridEntity entity : buckets[width * cy + cx]) {
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
        if (!entity.isCrownTower()) {
          partitioned.add(entity);
        }
      }
      for (GridEntity entity : result) {
        if (entity.isCrownTower()) {
          partitioned.add(entity);
        }
      }
      return partitioned;
    }
    return result;
  }

  /**
   * The bits of the bucket rows from {@code yLow} to {@code yHigh}, both included, cut to the
   * arena's rows; none when nothing of the range is on the arena.
   */
  private long rowRange(int yLow, int yHigh) {
    int low = Math.max(yLow, 0);
    int highRow = Math.min(yHigh, high - 1);
    if (low > highRow) {
      return 0;
    }
    return (-1L >>> (Long.SIZE - 1 - (highRow - low))) << low;
  }

  /**
   * Whether a list holds this very entity. A query's result holds the entities it has accepted, a
   * few at most, so looking through it is cheaper than keeping a set of them.
   */
  private static boolean holds(List<GridEntity> list, GridEntity entity) {
    for (int i = 0, size = list.size(); i < size; i++) {
      if (list.get(i) == entity) {
        return true;
      }
    }
    return false;
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
   * Moves the crown towers to the end of the result. The list is scanned from its last entry down
   * to its first, so a crown tower found nearer the front ends up after one found nearer the back.
   */
  private static void moveKingsLast(List<GridEntity> result) {
    for (int i = result.size() - 1; i >= 0; i--) {
      GridEntity entity = result.get(i);
      if (entity.isCrownTower()) {
        result.remove(i);
        result.add(entity);
      }
    }
  }
}
