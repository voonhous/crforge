package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * The view of the moving entity that {@link GridMove} consults before it lets a step cross into the
 * next cell.
 *
 * <p>The grid move blocks water for one case only: a type-5 entity that is not air and is in the
 * placing state. A type-5 entity in the spawn-pathfinding state passes the water test outright when
 * it carries the water permission.
 *
 * @param type virtual type of the entity; characters and crown towers are 5 and nothing else is
 *     tested for water at all
 * @param state the entity's current state
 * @param air whether the entity flies, in which case water never blocks it
 * @param waterPermission whether the entity may enter water while spawn pathfinding
 */
public record GridMoveEntity(int type, int state, boolean air, boolean waterPermission) {

  /** Virtual type of characters and crown towers, the only type the water test looks at. */
  public static final int CHARACTER_TYPE = 5;

  /**
   * The view of an ordinary character: its live type, state and air flag, with the
   * spawn-pathfinding water permission taken from its configuration.
   */
  public static GridMoveEntity of(GridEntity entity, MovementConfig config) {
    return new GridMoveEntity(
        entity.getType(),
        entity.getState(),
        entity.isAir(),
        config.entersWaterWhileSpawnPathfinding());
  }

  /** True when this entity is a character being placed, which is when water blocks its step. */
  public boolean placing() {
    return type == CHARACTER_TYPE && state == GridEntityState.DEPLOYING;
  }
}
