package org.crforge.core.pathfinding.state;

/**
 * What a unit removed from play follows: the object whose position its state visit copies onto it
 * each tick while it is pulled, a hooking projectile.
 */
public interface FollowedObject {

  /** Position along the arena's width, in game units. */
  int getX();

  /** Position along the arena's length, in game units. */
  int getY();
}
