package org.crforge.core.battle.action;

import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * The entity an action holder belongs to, as the leaf actions that act on their own owner see it:
 * its hit points, and the variables expressions read.
 */
public interface ActionOwner {

  /** The owner's hit points, or null for an owner without them. */
  HitPoints actionHitPoints();

  /** The value a variable holds for the owner; 0 for one never written. */
  int variable(int key);

  /** Writes a variable for the owner, replacing what it held. */
  void setVariable(int key, int value);

  /** True while the owner is alive; an owner without hit points counts as alive. */
  default boolean actionAlive() {
    return HitPoints.alive(actionHitPoints());
  }

  /** The owner's level, packed against its rarity. */
  default int actionPackedLevel() {
    throw new UnsupportedOperationException("this owner has no level");
  }

  /**
   * Changes the owner's level, as a level-changing action does.
   *
   * @param packed the new level, packed
   */
  default void changeLevel(int packed) {
    throw new UnsupportedOperationException("this owner's level cannot change");
  }

  /** True for a king tower, whose heals stop one short of its maximum. */
  default boolean kingTower() {
    return false;
  }

  /**
   * Kills the owner, as a hit of its whole hit points that ignores the battle's holds.
   *
   * @param killer the entity that caused it, or null for none
   */
  void killBy(ActionOwner killer);

  /**
   * Queues a typed hit on the owner, which the battle deals once per tick after the post-hooks.
   *
   * @param source the entity that deals it, or null for none
   * @param amount the amount, before the type's pipeline
   * @param type the hit's damage type
   */
  void queueTypedHit(ActionOwner source, int amount, DamageType type);
}
