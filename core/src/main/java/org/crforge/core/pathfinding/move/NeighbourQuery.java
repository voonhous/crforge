package org.crforge.core.pathfinding.move;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;

/**
 * Answers "which entities are near this point" for the two passes that need neighbours.
 *
 * <p>The push pass and the avoidance handler each ask their own question at the moment they run:
 * the push pass around the unit's own position with its collision radius plus a small margin, the
 * avoidance handler around the point one facing vector ahead of the unit with its collision radius
 * capped at 500 game units. Both questions are the plain circle test with no ordering, no type mask
 * and no team exclusion, and both may answer with the asking unit itself, which the passes skip.
 *
 * <p>The answer is borrowed: a caller hands it back with {@link #release(List)} as soon as the pass
 * that asked for it has finished, because the standard game's index lends out a fixed number of
 * result lists.
 */
@FunctionalInterface
public interface NeighbourQuery {

  /** The one answer a unit with nothing around it gets. */
  NeighbourQuery NONE = (x, y, radius) -> List.of();

  /**
   * The entities whose collision circle reaches within {@code radius} game units of the point, in
   * the order the index walks them.
   *
   * @param x point along the arena's width, in game units
   * @param y point along the arena's length, in game units
   * @param radius query radius in game units
   */
  List<GridEntity> near(int x, int y, int radius);

  /** Hands a previous answer back. The default does nothing, which suits a fixed answer. */
  default void release(List<GridEntity> result) {
    // Nothing to hand back by default.
  }

  /** A query that always answers with the same list, whatever is asked of it. */
  static NeighbourQuery fixed(List<GridEntity> entities) {
    return (x, y, radius) -> entities;
  }
}
