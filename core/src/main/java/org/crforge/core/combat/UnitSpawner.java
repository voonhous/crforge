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
  void spawnUnit(float x, float y, Team team, TroopStats stats, int level, float deployTime);
}
