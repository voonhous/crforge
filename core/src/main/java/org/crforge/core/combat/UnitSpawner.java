package org.crforge.core.combat;

import org.crforge.core.card.TroopStats;
import org.crforge.core.player.Team;

/**
 * Functional interface for spawning units. Used by CombatSystem for projectile spawn-on-impact
 * (e.g. PhoenixFireball -> PhoenixEgg, GoblinBarrel -> Goblins) without depending on SpawnerSystem
 * directly.
 */
@FunctionalInterface
public interface UnitSpawner {
  /** Spawns a unit at game-unit coordinates (x, y). */
  void spawnUnit(int x, int y, Team team, TroopStats stats, int level, float deployTime);
}
