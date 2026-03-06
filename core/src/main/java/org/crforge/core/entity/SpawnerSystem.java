package org.crforge.core.entity;

import java.util.Collections;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

public class SpawnerSystem {

  private final GameState gameState;
  private final CombatSystem combatSystem;

  public SpawnerSystem(GameState gameState, CombatSystem combatSystem) {
    this.gameState = gameState;
    this.combatSystem = combatSystem;
  }

  /**
   * Legacy constructor for tests that don't need death damage.
   */
  public SpawnerSystem(GameState gameState) {
    this(gameState, null);
  }

  public void update(float deltaTime) {
    // Iterate only alive entities
    for (Entity entity : gameState.getAliveEntities()) {
      if (isDeploying(entity)) {
        continue;
      }

      SpawnerComponent spawner = entity.getSpawner();

      // Skip if entity doesn't have this component
      if (spawner == null) {
        continue;
      }

      // Only tick periodic spawning for entities with live spawn capability
      if (spawner.hasLiveSpawn() && spawner.tick(deltaTime)) {
        spawnUnit(entity, spawner.getSpawnStats());
      }
    }
  }

  private boolean isDeploying(Entity entity) {
    if (entity instanceof Troop troop) {
      return troop.isDeploying();
    }
    if (entity instanceof Building building) {
      return building.isDeploying();
    }
    return false;
  }

  // Called by GameState.processDeaths() or GameEngine
  public void onDeath(Entity entity) {
    SpawnerComponent spawner = entity.getSpawner();

    if (spawner != null) {
      // 1. Apply death damage AOE (e.g. Golem, Ice Golem explode on death)
      if (spawner.getDeathDamage() > 0 && combatSystem != null) {
        combatSystem.applySpellDamage(
            entity.getTeam(),
            entity.getPosition().getX(),
            entity.getPosition().getY(),
            spawner.getDeathDamage(),
            spawner.getDeathDamageRadius(),
            Collections.emptyList());
      }

      // 2. Handle resolved death spawns (e.g. Golem -> 2 Golemites)
      for (DeathSpawnEntry entry : spawner.getDeathSpawns()) {
        for (int i = 0; i < entry.count(); i++) {
          spawnUnit(entity, entry.stats(), entry.radius());
        }
      }

      // 3. Fallback: legacy death spawn via deathSpawnCount + spawnStats
      if (spawner.getDeathSpawns().isEmpty() && spawner.getDeathSpawnCount() > 0) {
        for (int i = 0; i < spawner.getDeathSpawnCount(); i++) {
          spawnUnit(entity, spawner.getSpawnStats(), true);
        }
      }
    }

    // 4. Handle Effects (e.g. Mother Witch CURSE -> Cursed Hog)
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
    doSpawn(source.getPosition(), source.getCollisionRadius() * 2, source.getTeam(), stats,
        isDeathSpawn);
  }

  /**
   * Spawn a death spawn unit with a specific spread radius.
   */
  private void spawnUnit(Entity source, TroopStats stats, float spreadRadius) {
    if (stats == null) {
      return;
    }
    float effectiveSpread = spreadRadius > 0 ? spreadRadius : source.getCollisionRadius() * 2;
    doSpawn(source.getPosition(), effectiveSpread, source.getTeam(), stats, true);
  }

  private void spawnEffectUnit(Entity victim, TroopStats stats, Team ownerTeam) {
    if (stats == null) {
      return;
    }
    // Effect spawns (like Cursed Hogs) appear at the victim's location but belong to the effect owner
    doSpawn(victim.getPosition(), victim.getCollisionRadius() * 2, ownerTeam, stats, true);
  }

  private void doSpawn(Position origin, float originDiameter, Team team, TroopStats stats,
      boolean isDeathSpawn) {
    // Use origin to determine spawn location
    // Add simple random spread if it's a death spawn to prevent stacking perfectly
    float spread = isDeathSpawn ? (float) (Math.random() - 0.5) * originDiameter : 0;

    float x = origin.getX() + spread;
    float y = origin.getY() + spread;

    float initialLoad = stats.isNoPreload() ? 0f : stats.getLoadTime();
    Combat combat = Combat.builder()
        .damage(stats.getDamage())
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .loadTime(stats.getLoadTime())
        .accumulatedLoadTime(initialLoad)
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .build();

    Troop unit = Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(x, y))
        .health(new Health(stats.getHealth()))
        .movement(new Movement(
            stats.getSpeed(),
            stats.getMass(),
            stats.getCollisionRadius(),
            stats.getVisualRadius(),
            stats.getMovementType()))
        .combat(combat)
        .deployTime(0.5f) // Fast deploy for spawned units
        .build();

    gameState.spawnEntity(unit);
  }
}
