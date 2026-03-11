package org.crforge.core.entity;

import java.util.Collections;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
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
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

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

      // Skip spawner tick while stunned/frozen (timer pauses, does not reset)
      if (entity.getMovement().isMovementDisabled()) {
        continue;
      }

      // Self-destruct: kill bomb entities once their deploy phase finishes
      if (spawner.isSelfDestruct()) {
        entity.getHealth().takeDamage(entity.getHealth().getCurrent());
        continue;
      }

      // Only tick periodic spawning for entities with live spawn capability
      if (spawner.hasLiveSpawn() && spawner.tick(deltaTime)) {
        int spawnIndex = spawner.getLastSpawnIndex();
        int total = spawner.getUnitsPerWave();
        float formationRadius = spawner.getFormationRadius();
        float collisionRadius = spawner.getSpawnStats().getCollisionRadius();
        Vector2 offset = FormationLayout.calculateOffset(
            spawnIndex, total, formationRadius, collisionRadius);
        doSpawn(entity.getPosition(), offset, entity.getTeam(), spawner.getSpawnStats(),
            spawner.getRarity(), spawner.getLevel());
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

        // Apply knockback to nearby enemies (e.g. Golem, Giant Skeleton death explosion)
        if (spawner.getDeathPushback() > 0) {
          applyDeathKnockback(entity, spawner);
        }
      }

      // 2. Handle resolved death spawns (e.g. Golem -> 2 Golemites)
      for (DeathSpawnEntry entry : spawner.getDeathSpawns()) {
        for (int i = 0; i < entry.count(); i++) {
          Vector2 offset = FormationLayout.calculateOffset(
              i, entry.count(), entry.radius(), entry.stats().getCollisionRadius());
          doSpawn(entity.getPosition(), offset, entity.getTeam(), entry.stats(),
              spawner.getRarity(), spawner.getLevel());
        }
      }

      // 3. Fallback: legacy death spawn via deathSpawnCount + spawnStats
      if (spawner.getDeathSpawns().isEmpty() && spawner.getDeathSpawnCount() > 0) {
        for (int i = 0; i < spawner.getDeathSpawnCount(); i++) {
          Vector2 offset = FormationLayout.calculateOffset(
              i, spawner.getDeathSpawnCount(), spawner.getFormationRadius(),
              spawner.getSpawnStats().getCollisionRadius());
          doSpawn(entity.getPosition(), offset, entity.getTeam(), spawner.getSpawnStats(),
              spawner.getRarity(), spawner.getLevel());
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

  private void spawnEffectUnit(Entity victim, TroopStats stats, Team ownerTeam) {
    if (stats == null) {
      return;
    }
    // Effect spawns (like Cursed Hogs) appear at the victim's location with no offset.
    // Uses level 1 / Common defaults -- Curse spawns need complex mechanics for proper scaling.
    doSpawn(victim.getPosition(), new Vector2(0, 0), ownerTeam, stats,
        Rarity.COMMON, 1);
  }

  private static final float KNOCKBACK_DURATION = 0.5f;
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  /**
   * Applies knockback to nearby enemies from a death explosion.
   * Buildings and entities with ignorePushback are immune.
   */
  private void applyDeathKnockback(Entity source, SpawnerComponent spawner) {
    float centerX = source.getPosition().getX();
    float centerY = source.getPosition().getY();
    float radius = spawner.getDeathDamageRadius();
    float pushback = spawner.getDeathPushback();
    Team enemyTeam = source.getTeam().opposite();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam || !entity.isTargetable()) {
        continue;
      }
      if (entity.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      Movement movement = entity.getMovement();
      if (movement == null || movement.isIgnorePushback()) {
        continue;
      }

      float distSq = entity.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = radius + entity.getCollisionRadius();
      if (distSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      // Direction from death center to target
      float dx = entity.getPosition().getX() - centerX;
      float dy = entity.getPosition().getY() - centerY;
      float dist = (float) Math.sqrt(dx * dx + dy * dy);
      float dirX = dist > 0.001f ? dx / dist : 0f;
      float dirY = dist > 0.001f ? dy / dist : 1f;

      movement.startKnockback(dirX, dirY, pushback, KNOCKBACK_DURATION, KNOCKBACK_MAX_TIME);
    }
  }

  private void doSpawn(Position origin, Vector2 offset, Team team, TroopStats stats,
      Rarity rarity, int level) {
    float x = origin.getX() + offset.getX();
    float y = origin.getY() + offset.getY();

    // Bomb entities (health=0) survive their deploy phase with 1 HP, then self-destruct
    boolean isBomb = stats.getHealth() <= 0;
    int baseHp = isBomb ? 1 : stats.getHealth();

    int scaledHp = LevelScaling.scaleCard(baseHp, rarity, level);
    int scaledDamage = LevelScaling.scaleCard(stats.getDamage(), rarity, level);
    int scaledShield = stats.getShieldHitpoints() > 0
        ? LevelScaling.scaleCard(stats.getShieldHitpoints(), rarity, level) : 0;

    float initialLoad = stats.isNoPreload() ? 0f : stats.getLoadTime();
    Combat combat = Combat.builder()
        .damage(scaledDamage)
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .loadTime(stats.getLoadTime())
        .accumulatedLoadTime(initialLoad)
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .selfAsAoeCenter(stats.isSelfAsAoeCenter())
        .build();

    // Use deployTime from stats for bomb entities (e.g. 3.0s for BalloonBomb falling),
    // otherwise spawned units deploy instantly
    float deployTime = isBomb ? stats.getDeployTime() : 0f;

    // Build SpawnerComponent for units with death mechanics or bomb behavior
    boolean hasDeathMechanics = stats.getDeathDamage() > 0 || !stats.getDeathSpawns().isEmpty();
    SpawnerComponent spawner = null;
    if (hasDeathMechanics || isBomb) {
      int scaledDeathDamage = stats.getDeathDamage() > 0
          ? LevelScaling.scaleCard(stats.getDeathDamage(), rarity, level) : 0;

      spawner = SpawnerComponent.builder()
          .deathDamage(scaledDeathDamage)
          .deathDamageRadius(stats.getDeathDamageRadius())
          .deathPushback(stats.getDeathPushback())
          .deathSpawns(stats.getDeathSpawns())
          .rarity(rarity)
          .level(level)
          .selfDestruct(isBomb)
          .build();
    }

    Troop unit = Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(x, y))
        .health(new Health(scaledHp, scaledShield))
        .movement(new Movement(
            stats.getSpeed(),
            stats.getMass(),
            stats.getCollisionRadius(),
            stats.getVisualRadius(),
            stats.getMovementType()))
        .combat(combat)
        .deployTime(deployTime)
        .deployTimer(deployTime)
        .spawner(spawner)
        .level(level)
        .build();

    gameState.spawnEntity(unit);
  }
}
