package org.crforge.core.battle.spawn;

import java.util.List;
import org.crforge.core.battle.action.ActionHolder;

/**
 * An object of the battle a spawn can come from: it answers as a spawn object, it has an action
 * holder, and it reaches the battle's spawner.
 */
public interface SpawnHost extends SpawnObject {

  /** The object's name, which the children it spawns are named after. */
  String name();

  /** The object's action holder. */
  ActionHolder actionHolder();

  /**
   * Spawns the children the block describes, in the battle this object belongs to.
   *
   * @param arguments the block a spawn row's perform works out
   * @return the children spawned, in the order they were made
   */
  List<SpawnHost> spawnCharacters(SpawnArguments arguments);

  /**
   * Links a child this object spawned into its group, right after itself, so the group reads newest
   * first. Only a character keeps a group.
   *
   * @param child the child
   */
  default void linkIntoGroup(SpawnHost child) {
    throw new UnsupportedOperationException(name() + " keeps no group");
  }
}
