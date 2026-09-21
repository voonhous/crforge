package org.crforge.desktop.render;

import java.util.List;
import org.crforge.core.match.PathfindingMode;

/**
 * What the screen tells the renderer about the grid pathfinding overlays each frame.
 *
 * <p>The mode is two values, not one, because a mode change only takes effect when the match is
 * rebuilt: the active mode is the one the running match was created with and the pending mode is
 * the one the next reset will use.
 *
 * @param activeMode the rules the running match is under
 * @param pendingMode the rules the next reset will build the match with
 * @param hoverCellCol the routing cell column under the mouse, or a value outside the grid
 * @param hoverCellRow the routing cell row under the mouse
 * @param golden the golden scenario's trajectory, or {@link GoldenOverlay#none()}
 * @param scenarioLines the status lines the golden scenario contributes, possibly empty
 */
public record GridDebugStatus(
    PathfindingMode activeMode,
    PathfindingMode pendingMode,
    int hoverCellCol,
    int hoverCellRow,
    GoldenOverlay golden,
    List<String> scenarioLines) {

  /** No grid information at all, for a screen that does not offer the overlays. */
  public static GridDebugStatus none() {
    return new GridDebugStatus(null, null, -1, -1, GoldenOverlay.none(), List.of());
  }
}
