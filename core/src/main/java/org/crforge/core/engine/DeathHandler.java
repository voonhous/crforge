package org.crforge.core.engine;

import org.crforge.core.entity.base.Entity;

/**
 * Functional interface for handling entity death events. Used by GameState to delegate death
 * processing (death spawns, death damage, etc.) without depending on SpawnerSystem directly.
 */
@FunctionalInterface
public interface DeathHandler {
  void onDeath(Entity entity);
}
