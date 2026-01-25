package org.crforge.core.entity;

import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

public class SpawnerSystem {

  private final GameState gameState;

  public SpawnerSystem(GameState gameState) {
    this.gameState = gameState;
  }

  public void update(float deltaTime) {
    // Iterate only alive entities
    for (Entity entity : gameState.getAliveEntities()) {
      SpawnerComponent spawner = entity.getSpawner();

      // Skip if entity doesn't have this component
      if (spawner == null) {
        continue;
      }

      // Handle Interval Spawning
      if (spawner.tick(deltaTime)) {
        spawnUnit(entity, spawner.getSpawnStats());
      }
    }
  }

  // Called by GameState.processDeaths() or GameEngine
  public void onDeath(Entity entity) {
    SpawnerComponent spawner = entity.getSpawner();
    if (spawner != null && spawner.getDeathSpawnCount() > 0) {
      for (int i = 0; i < spawner.getDeathSpawnCount(); i++) {
        spawnUnit(entity, spawner.getSpawnStats(), true);
      }
    }
  }

  private void spawnUnit(Entity source, TroopStats stats) {
    spawnUnit(source, stats, false);
  }

  private void spawnUnit(Entity source, TroopStats stats, boolean isDeathSpawn) {
    if (stats == null) {
      return;
    }

    // Use source.getPosition() to determine spawn location
    // Add simple random spread if it's a death spawn to prevent stacking perfectly
    float spread = isDeathSpawn ? (float) (Math.random() - 0.5) * source.getSize() : 0;

    float x = source.getPosition().getX() + spread;
    float y = source.getPosition().getY() + spread;

    Combat combat = Combat.builder()
        .damage(stats.getDamage())
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .ranged(stats.isRanged())
        .build();

    Troop unit = Troop.builder()
        .name(stats.getName())
        .team(source.getTeam())
        .position(new Position(x, y))
        .health(new Health(stats.getHealth()))
        .movement(new Movement(stats.getSpeed(), stats.getMass(), stats.getSize(),
            stats.getMovementType()))
        .combat(combat)
        .deployTime(0.5f) // Fast deploy for spawned units
        .build();

    gameState.spawnEntity(unit);
  }
}
