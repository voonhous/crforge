package org.crforge.desktop.render;

import java.util.List;

/**
 * A golden scenario's reference trajectory as the renderer needs it: plain positions in game units,
 * with no dependency on where they came from.
 *
 * @param path every reference position, one per tick, in tick order; empty when no scenario is
 *     being replayed
 * @param current the reference position for the tick currently being simulated, or null when the
 *     trajectory does not reach that tick
 * @param deviation the reference position of the first tick the live unit did not match, or null
 *     while it has matched every tick
 */
public record GoldenOverlay(List<int[]> path, int[] current, int[] deviation) {

  /** Nothing to draw. */
  public static GoldenOverlay none() {
    return new GoldenOverlay(List.of(), null, null);
  }

  /** True when there is no trajectory to draw at all. */
  public boolean isEmpty() {
    return path.isEmpty();
  }
}
