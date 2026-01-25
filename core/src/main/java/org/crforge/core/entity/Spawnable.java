package org.crforge.core.entity;

import java.util.function.Consumer;

/**
 * Interface for entities that can spawn other entities into the game world (e.g., Tombstone, Goblin
 * Hut, Witch).
 */
public interface Spawnable {

  void setSpawnCallback(Consumer<Entity> callback);
}
