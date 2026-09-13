package org.crforge.core.entity;

import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.GameUnits;

public class SpawnerSystem {

  private final GameState gameState;
  private final SpawnFactory spawnFactory;

  public SpawnerSystem(GameState gameState, SpawnFactory spawnFactory) {
    this.gameState = gameState;
    this.spawnFactory = spawnFactory;
  }

  /**
   * Spawns a unit at a specific position. Used by CombatSystem for projectile spawn-on-impact (e.g.
   * PhoenixFireball spawns PhoenixEgg, GoblinBarrel spawns Goblins). Coordinates are game units.
   */
  public void spawnUnit(int x, int y, Team team, TroopStats stats, int level, float deployTime) {
    spawnFactory.doSpawn(
        new Position(x, y), FormationLayout.Offset.ZERO, team, stats, level, deployTime, false);
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

      // Skip spawner tick while stunned/frozen (timer pauses, does not reset)
      if (entity.getMovement().isMovementDisabled()) {
        continue;
      }

      // Skip spawner tick if aggro-gated and no enemies are in detection range
      if (spawner.isSpawnOnAggro() && !hasEnemyInRange(entity, spawner.getAggroDetectionRange())) {
        continue;
      }

      // Self-destruct: kill bomb entities once their deploy phase finishes
      if (spawner.isSelfDestruct()) {
        entity.getHealth().takeDamage(entity.getHealth().getCurrent());
        continue;
      }

      // Only tick periodic spawning for entities with live spawn capability
      if (spawner.hasLiveSpawn() && spawner.tick(deltaTime)) {
        FormationLayout.Offset offset;
        if (spawner.isSpawnOnAggro()) {
          // Spawn adjacent to building, toward the nearest enemy
          Entity nearestEnemy = findNearestEnemy(entity, spawner.getAggroDetectionRange());
          offset = calculateAggroSpawnOffset(entity, nearestEnemy);
        } else {
          int spawnIndex = spawner.getLastSpawnIndex();
          int total = spawner.getUnitsPerWave();
          int formationRadius = spawner.getFormationRadius();
          int collisionRadius = spawner.getSpawnStats().getCollisionRadius();
          offset =
              FormationLayout.calculateOffset(spawnIndex, total, formationRadius, collisionRadius);
        }

        // Propagate clone status: if parent is a clone, children are clones too
        boolean parentIsClone = entity instanceof Troop troop && troop.isClone();
        spawnFactory.doSpawn(
            entity.getPosition(),
            offset,
            entity.getTeam(),
            spawner.getSpawnStats(),
            spawner.getLevel(),
            0f,
            parentIsClone);

        // Track spawn count and enforce spawn limit (e.g. PhoenixEgg spawns once then dies)
        spawner.setTotalSpawned(spawner.getTotalSpawned() + 1);
        if (spawner.getSpawnLimit() > 0 && spawner.getTotalSpawned() >= spawner.getSpawnLimit()) {
          if (spawner.isDestroyAtLimit()) {
            entity.getHealth().takeDamage(entity.getHealth().getCurrent());
          }
        }
      }
    }
  }

  /**
   * Returns true if any targetable enemy entity is within the given range of the source entity.
   * Used for aggro-gated spawning (e.g. GoblinHut_Rework only spawns when enemies are nearby).
   */
  private boolean hasEnemyInRange(Entity source, int range) {
    return findNearestEnemy(source, range) != null;
  }

  /**
   * Returns the nearest targetable enemy entity within the given range, or null if none found. Used
   * for aggro-gated spawning to determine both presence and direction of nearby enemies. The range
   * is center-to-center in game units.
   */
  private Entity findNearestEnemy(Entity source, int range) {
    Team enemyTeam = source.getTeam().opposite();
    Entity nearest = null;
    long nearestDistSq = Long.MAX_VALUE;
    for (Entity other : gameState.getAliveEntities()) {
      if (other.getTeam() != enemyTeam || !other.isTargetable()) {
        continue;
      }
      EntityType type = other.getEntityType();
      if (type != EntityType.TROOP && type != EntityType.BUILDING && type != EntityType.TOWER) {
        continue;
      }
      long distSq = source.getPosition().distanceSquaredTo(other.getPosition());
      if (GameUnits.withinRadius(distSq, range) && distSq < nearestDistSq) {
        nearestDistSq = distSq;
        nearest = other;
      }
    }
    return nearest;
  }

  /**
   * Calculates a spawn offset toward the target enemy so the spawned unit appears at the building's
   * edge facing the threat. Returns (0,0) if the target is null or overlapping.
   */
  private FormationLayout.Offset calculateAggroSpawnOffset(Entity building, Entity target) {
    if (target == null) {
      return FormationLayout.Offset.ZERO;
    }
    double dx = (double) target.getPosition().getX() - building.getPosition().getX();
    double dy = (double) target.getPosition().getY() - building.getPosition().getY();
    double dist = Math.hypot(dx, dy);
    if (dist == 0) {
      return FormationLayout.Offset.ZERO;
    }
    // Offset by building's collision radius so the unit spawns at the building's edge
    int spawnDist = building.getCollisionRadius();
    return new FormationLayout.Offset(
        GameUnits.round(dx / dist * spawnDist), GameUnits.round(dy / dist * spawnDist));
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
}
