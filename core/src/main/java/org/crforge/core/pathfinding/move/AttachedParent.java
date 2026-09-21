package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;

/**
 * The entity a unit is attached to, together with that unit's share of the placement arc.
 *
 * <p>An attached unit does not route at all: every movement visit places it on a circle around its
 * parent and copies or turns toward the parent's facing.
 *
 * @param entity the parent whose position and facing the attached unit follows
 * @param shareAngle the attached unit's share of the placement arc, in the same units as the
 *     configured maximum spawn angle; it is multiplied by that maximum and divided by 360
 */
public record AttachedParent(GridEntity entity, int shareAngle) {}
