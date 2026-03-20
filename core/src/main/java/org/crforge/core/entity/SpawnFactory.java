package org.crforge.core.entity;

import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.AttackStateMachine;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.Vector2;

/**
 * Builds and spawns Troop entities from TroopStats. Handles level scaling, combat component
 * assembly, spawner wiring, and clone logic. Used by both live spawning and death spawning paths.
 */
public class SpawnFactory {

  private final GameState gameState;

  public SpawnFactory(GameState gameState) {
    this.gameState = gameState;
  }

  void doSpawn(
      Position origin,
      Vector2 offset,
      Team team,
      TroopStats stats,
      int level,
      float deathSpawnDeployTime,
      boolean asClone) {
    float x = origin.getX() + offset.getX();
    float y = origin.getY() + offset.getY();

    // Bomb entities (health=0) survive their deploy phase with 1 HP, then self-destruct
    boolean isBomb = stats.getHealth() <= 0;
    int baseHp = isBomb ? 1 : stats.getHealth();

    int scaledHp = LevelScaling.scaleCard(baseHp, level);
    int scaledDamage = LevelScaling.scaleCard(stats.getDamage(), level);
    int scaledShield =
        stats.getShieldHitpoints() > 0
            ? LevelScaling.scaleCard(stats.getShieldHitpoints(), level)
            : 0;

    // Clone offspring: 1 HP, shield capped to 1
    if (asClone) {
      scaledHp = 1;
      scaledShield = scaledShield > 0 ? 1 : 0;
    }

    // Skip Combat component for units that cannot deal damage (e.g. PhoenixEgg).
    // Without it they won't acquire targets, attack, or play attack animations.
    Combat combat = null;
    if (scaledDamage > 0 || stats.getProjectile() != null) {
      float initialLoad = stats.isNoPreload() ? 0f : stats.getLoadTime();
      combat =
          Combat.builder()
              .damage(scaledDamage)
              .range(stats.getRange())
              .sightRange(stats.getSightRange())
              .attackCooldown(stats.getAttackCooldown())
              .loadTime(stats.getLoadTime())
              .attackState(AttackStateMachine.withLoad(initialLoad))
              .aoeRadius(stats.getAoeRadius())
              .targetType(stats.getTargetType())
              .selfAsAoeCenter(stats.isSelfAsAoeCenter())
              .attackDashTime(stats.getAttackDashTime())
              .targetOnlyTroops(stats.isTargetOnlyTroops())
              .ignoreTargetsWithBuff(stats.getIgnoreTargetsWithBuff())
              .build();
    }

    // Use deployTime from stats for bomb entities (e.g. 3.0s for BalloonBomb falling),
    // deathSpawnDeployTime for death-spawned units (e.g. Goblin Cage's GoblinBrawler),
    // otherwise spawned units deploy instantly.
    // Include deployDelay (spawn animation delay) in the total deploy duration so that
    // Troop.onSpawn() does not zero the deploy timer.
    float baseDeployTime = isBomb ? stats.getDeployTime() : deathSpawnDeployTime;
    float deployTime = baseDeployTime + stats.getDeployDelay();

    // Build SpawnerComponent for units with death mechanics, bomb behavior, or liveSpawn
    boolean hasDeathMechanics =
        stats.getDeathDamage() > 0
            || !stats.getDeathSpawns().isEmpty()
            || stats.getDeathAreaEffect() != null
            || stats.getManaOnDeathForOpponent() > 0
            || stats.getDeathSpawnProjectile() != null;
    boolean hasLiveSpawn = stats.getLiveSpawn() != null && stats.getSpawnTemplate() != null;
    SpawnerComponent spawner = null;
    if (hasDeathMechanics || isBomb || hasLiveSpawn) {
      int scaledDeathDamage =
          stats.getDeathDamage() > 0 ? LevelScaling.scaleCard(stats.getDeathDamage(), level) : 0;

      // Scale death spawn projectile damage
      ProjectileStats deathProjStats = null;
      if (stats.getDeathSpawnProjectile() != null) {
        deathProjStats =
            stats
                .getDeathSpawnProjectile()
                .withDamage(
                    LevelScaling.scaleCard(stats.getDeathSpawnProjectile().getDamage(), level));
        // Preserve the resolved spawn character reference
        if (stats.getDeathSpawnProjectile().getSpawnCharacter() != null) {
          deathProjStats =
              deathProjStats.withSpawnCharacter(
                  stats.getDeathSpawnProjectile().getSpawnCharacter());
        }
      }

      SpawnerComponent.SpawnerComponentBuilder spawnerBuilder =
          SpawnerComponent.builder()
              .deathDamage(scaledDeathDamage)
              .deathDamageRadius(stats.getDeathDamageRadius())
              .deathPushback(stats.getDeathPushback())
              .deathSpawns(stats.getDeathSpawns())
              .deathAreaEffect(stats.getDeathAreaEffect())
              .manaOnDeathForOpponent(stats.getManaOnDeathForOpponent())
              .deathSpawnProjectile(deathProjStats)
              .level(level)
              .selfDestruct(isBomb);

      // Wire liveSpawn config (e.g. PhoenixEgg spawns PhoenixNoRespawn after 4.3s)
      if (hasLiveSpawn) {
        LiveSpawnConfig ls = stats.getLiveSpawn();
        spawnerBuilder
            .spawnInterval(ls.spawnInterval())
            .spawnPauseTime(ls.spawnPauseTime())
            .unitsPerWave(ls.spawnNumber())
            .spawnStartTime(ls.spawnStartTime())
            .currentTimer(ls.spawnStartTime())
            .spawnStats(stats.getSpawnTemplate())
            .formationRadius(ls.spawnRadius())
            .spawnLimit(ls.spawnLimit())
            .destroyAtLimit(ls.destroyAtLimit())
            .spawnOnAggro(ls.spawnOnAggro())
            .aggroDetectionRange(ls.spawnOnAggro() ? stats.getRange() : 0f);
      }

      spawner = spawnerBuilder.build();
    }

    Troop unit =
        Troop.builder()
            .name(stats.getName())
            .team(team)
            .position(new Position(x, y))
            .health(new Health(scaledHp, scaledShield))
            .movement(
                new Movement(
                    stats.getSpeed(),
                    stats.getMass(),
                    stats.getCollisionRadius(),
                    stats.getVisualRadius(),
                    stats.getMovementType()))
            .combat(combat)
            .deployTime(deployTime)
            .deployTimer(deployTime)
            .spawner(spawner)
            .transformConfig(stats.getTransformConfig())
            .lifeTimer(stats.getLifeTime())
            .level(level)
            .clone(asClone)
            .build();

    gameState.spawnEntity(unit);
  }
}
