package org.crforge.core.pathfinding.index;

/**
 * One circle or box query against the {@link SpatialIndex}.
 *
 * <p>The point and the radius are integer game units. The remaining four values pick the query's
 * behaviour and are passed by every caller as a fixed set of constants:
 *
 * <ul>
 *   <li>{@code halfHeight} zero selects a circle test, any other value a box test;
 *   <li>{@code kingsLast} moves the king towers to the end of the result;
 *   <li>{@code buildingAware} makes buildings test their square rather than their circle;
 *   <li>{@code typeMask}, when at least one, keeps only entities whose type bit is set;
 *   <li>{@code excludeTeam} drops every entity on that team, or -1 to keep all teams.
 * </ul>
 *
 * @param x query centre along the arena's width
 * @param y query centre along the arena's length
 * @param radius query radius. A negative radius does not by itself empty the answer: the bucket
 *     scan only gives up when the spans it derives invert, and the circle test still accepts an
 *     entity whose own collision radius more than covers the shortfall
 * @param halfHeight half height of the box test, or 0 for the circle test
 * @param kingsLast true to order king towers after everything else
 * @param buildingAware true to test buildings as squares
 * @param typeMask bit set of accepted entity types, or 0 to accept every type
 * @param excludeTeam team to drop, or -1 to keep every team
 */
public record SpatialQuery(
    int x,
    int y,
    int radius,
    int halfHeight,
    boolean kingsLast,
    boolean buildingAware,
    int typeMask,
    int excludeTeam) {

  /**
   * The query the target selector runs once per selection: a plain circle test around the unit that
   * accepts an entity whose centre is closer than the radius plus that entity's own collision
   * radius, keeps every type and every team, and orders king towers last.
   */
  public static SpatialQuery targetCandidates(int x, int y, int radius) {
    return new SpatialQuery(x, y, radius, 0, true, false, 0, -1);
  }
}
