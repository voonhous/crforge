package org.crforge.core.entity;

import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

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
    // 1. Handle Spawner Component (e.g. Golem -> Golemites, Tombstone -> Skeletons)
    SpawnerComponent spawner = entity.getSpawner();
    if (spawner != null && spawner.getDeathSpawnCount() > 0) {
      for (int i = 0; i < spawner.getDeathSpawnCount(); i++) {
        spawnUnit(entity, spawner.getSpawnStats(), true);
      }
    }

    // 2. Handle Effects (e.g. Mother Witch CURSE -> Cursed Hog)
    for (AppliedEffect effect : entity.getAppliedEffects()) {
      if (effect.getType() == StatusEffectType.CURSE && effect.getSpawnSpecies() != null) {
        // Spawn the unit for the OPPONENT of the dying unit
        // (i.e. the team of the Mother Witch who applied the curse)
        spawnEffectUnit(entity, effect.getSpawnSpecies(), entity.getTeam().opposite());
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
    // Standard spawns belong to the same team as the source
    doSpawn(source.getPosition(), source.getSize(), source.getTeam(), stats, isDeathSpawn);
  }

  private void spawnEffectUnit(Entity victim, TroopStats stats, Team ownerTeam) {
    if (stats == null) {
      return;
    }
    // Effect spawns (like Cursed Hogs) appear at the victim's location but belong to the effect owner
    doSpawn(victim.getPosition(), victim.getSize(), ownerTeam, stats, true);
  }

  private void doSpawn(Position origin, float originSize, Team team, TroopStats stats,
      boolean isDeathSpawn) {
    // Use origin to determine spawn location
    // Add simple random spread if it's a death spawn to prevent stacking perfectly
    float spread = isDeathSpawn ? (float) (Math.random() - 0.5) * originSize : 0;

    float x = origin.getX() + spread;
    float y = origin.getY() + spread;

    Combat combat = Combat.builder()
        .damage(stats.getDamage())
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .firstAttackCooldown(stats.getFirstAttackCooldown())
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .build();

    Troop unit = Troop.builder()
        .name(stats.getName())
        .team(team)
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
