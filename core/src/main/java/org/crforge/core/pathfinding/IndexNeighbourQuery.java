package org.crforge.core.pathfinding;

import java.util.List;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.crforge.core.pathfinding.index.SpatialQuery;
import org.crforge.core.pathfinding.move.NeighbourQuery;

/**
 * The neighbour answers the push pass and the avoidance handler pull, taken from this tick's
 * spatial index.
 *
 * <p>Both passes ask the same question with their own point and radius: the plain circle test that
 * keeps an entity whose own collision circle reaches within the radius of the point, in the index's
 * bucket order, with no king-tower ordering, no type mask and no team exclusion. The asking entity
 * is in the answer and each pass skips it itself.
 *
 * <p>The index lends out a fixed number of result lists, so every answer is handed back as soon as
 * the pass that asked for it has finished. When none is free the answer is the shared empty list,
 * which is never handed back.
 */
public final class IndexNeighbourQuery implements NeighbourQuery {

  private final SpatialIndex index;

  /**
   * @param index the spatial index, rebuilt once per tick before any pass asks
   */
  public IndexNeighbourQuery(SpatialIndex index) {
    this.index = index;
  }

  /** What a query answers when the index has no result list to lend. */
  private final List<GridEntity> noNeighbours = List.of();

  @Override
  public List<GridEntity> near(int x, int y, int radius) {
    List<GridEntity> result = index.query(new SpatialQuery(x, y, radius, 0, false, false, 0, -1));
    return result == null ? noNeighbours : result;
  }

  @Override
  public void release(List<GridEntity> result) {
    if (result != noNeighbours) {
      index.release(result);
    }
  }
}
