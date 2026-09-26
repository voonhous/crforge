package org.crforge.core.battle.spawn;

/**
 * A game object as a character spawn reads it: the owner of the action, the entity that caused it,
 * or the source of the spawn, which may be any of them.
 */
public interface SpawnObject {

  /** The object's position along the width, in game units. */
  int x();

  /** The object's position along the length, in game units. */
  int y();

  /** The object's side. */
  int side();

  /**
   * The object's kind: an area effect, a projectile or a character, as the entity kinds number
   * them.
   */
  int kind();

  /** True for an object that answers as a character, whose own columns the spawner then reads. */
  boolean isCharacter();

  /** The prestige a spawn that inherits it takes: a character's, or an area effect's. */
  int prestige();

  /** The object's level, packed against its own rarity. */
  int packedLevel();
}
