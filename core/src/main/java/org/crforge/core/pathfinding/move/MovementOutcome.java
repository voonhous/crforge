package org.crforge.core.pathfinding.move;

/**
 * What one displacement left behind: where the entity ended up and whether it arrived at the
 * waypoint it was aiming for.
 *
 * <p>The follower asks for a displacement and reads the answer back, rather than reading the entity
 * and the component directly, because the displacement runs through {@link MovementChain}.
 *
 * @param x the entity's position along the arena's width after the step, in game units
 * @param y the entity's position along the arena's length after the step, in game units
 * @param reached 1 when the remaining distance projected on the route direction dropped to the
 *     arrival threshold, which is what pops the route's last node
 */
public record MovementOutcome(int x, int y, int reached) {}
