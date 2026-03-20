package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
import lombok.Setter;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

/**
 * Handles the projectile hit pipeline: AOE/single-target damage, reflect check, chain lightning,
 * spawn sub-projectile, spawn area effect, and spawn character on impact.
 */
class ProjectileHitProcessor {

  // Tight formation radius for multi-unit spawn-on-impact (matches 3-Minion deploy pattern).
  // Physics collision pushes units out of overlapping buildings, creating the correct spread.
  private static final float SPAWN_ON_IMPACT_FORMATION_RADIUS = 0.577f;

  private final GameState gameState;
  private final AoeDamageService aoeDamageService;
  private final KnockbackHelper knockbackHelper;
  private final CombatAbilityBridge abilityBridge;

  @Setter @lombok.Getter private UnitSpawner unitSpawner;

  ProjectileHitProcessor(
      GameState gameState,
      AoeDamageService aoeDamageService,
      KnockbackHelper knockbackHelper,
      CombatAbilityBridge abilityBridge) {
    this.gameState = gameState;
    this.aoeDamageService = aoeDamageService;
    this.knockbackHelper = knockbackHelper;
    this.abilityBridge = abilityBridge;
  }

  void onProjectileHit(Projectile projectile) {
    int baseDamage = projectile.getDamage();
    int ctdp = projectile.getCrownTowerDamagePercent();

    if (projectile.isPositionTargeted()) {
      aoeDamageService.applySpellDamage(
          projectile.getTeam(),
          projectile.getTargetX(),
          projectile.getTargetY(),
          baseDamage,
          projectile.getAoeRadius(),
          projectile.getEffects(),
          ctdp);
    } else if (projectile.hasAoe()) {
      if (!projectile.isHoming()) {
        // Non-homing: AOE centered at the fixed landing position (where target was when fired)
        aoeDamageService.applySpellDamage(
            projectile.getTeam(),
            projectile.getFixedTargetX(),
            projectile.getFixedTargetY(),
            baseDamage,
            projectile.getAoeRadius(),
            projectile.getEffects(),
            ctdp);
      } else {
        Entity hTarget = projectile.getTarget();
        if (hTarget != null) {
          aoeDamageService.applySpellDamage(
              projectile.getTeam(),
              hTarget.getPosition().getX(),
              hTarget.getPosition().getY(),
              baseDamage,
              projectile.getAoeRadius(),
              projectile.getEffects(),
              ctdp);
        }
      }
    } else {
      Entity target = projectile.getTarget();
      int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, target, ctdp);
      // Apply pre-damage effects (e.g. Curse -- dying from damage should still trigger)
      aoeDamageService.applyEffects(
          target, aoeDamageService.filterEffects(projectile.getEffects(), false));
      aoeDamageService.dealDamage(target, effectiveDamage);
      // Apply post-damage effects (e.g. Ice Wizard SLOW, EWiz STUN)
      aoeDamageService.applyEffects(
          target, aoeDamageService.filterEffects(projectile.getEffects(), true));
    }

    // Apply knockback to entities hit by the projectile
    if (projectile.getPushback() > 0) {
      knockbackHelper.applyKnockback(projectile);
    }

    // Reflect: if a projectile hits a REFLECT target and the source is within reflect radius,
    // zap them
    if (!projectile.isPositionTargeted()) {
      Entity target = projectile.getTarget();
      int reflectDmg = abilityBridge.getReflectDamage(target);
      if (reflectDmg > 0
          && target instanceof Troop reflector
          && reflector.getAbility().getData() instanceof ReflectAbility reflect) {
        Entity source = projectile.getSource();
        if (source != null && source.isAlive()) {
          float dist = source.getPosition().distanceTo(reflector.getPosition());
          float effectiveRadius = reflect.reflectRadius() + source.getCollisionRadius();
          if (dist <= effectiveRadius) {
            abilityBridge.applyReflectDamage(reflector, source, reflectDmg, aoeDamageService);
          }
        }
      }
    }

    // Chain lightning: spawn sub-projectiles to nearby enemies
    if (projectile.getChainedHitCount() > 0 && projectile.getChainedHitRadius() > 0) {
      processChainLightning(projectile);
    }

    // Spawn sub-projectile on impact (Log rolling, Firecracker explosion)
    if (projectile.getSpawnProjectile() != null) {
      processSpawnProjectile(projectile);
    }

    // Spawn area effect on impact (Heal Spirit heal zone, etc.)
    if (projectile.getSpawnAreaEffect() != null) {
      spawnAreaEffectOnImpact(projectile);
    }

    // Spawn character on impact (e.g. PhoenixFireball spawns PhoenixEgg)
    if (projectile.getSpawnCharacterStats() != null && unitSpawner != null) {
      spawnCharacterOnImpact(projectile);
    }
  }

  /**
   * Spawns characters at the projectile's impact point (or current position for piercing expire).
   */
  void spawnCharacterOnImpact(Projectile projectile) {
    TroopStats stats = projectile.getSpawnCharacterStats();
    int count = Math.max(1, projectile.getSpawnCharacterCount());
    Rarity rarity =
        projectile.getSpawnCharacterRarity() != null
            ? projectile.getSpawnCharacterRarity()
            : Rarity.COMMON;
    int level = projectile.getSpawnCharacterLevel() > 0 ? projectile.getSpawnCharacterLevel() : 1;
    float deployTime = projectile.getSpawnDeployTime();

    // Determine impact position
    float centerX, centerY;
    if (projectile.isPiercing()) {
      // Piercing expire: spawn at current position (e.g. BarbLog Barbarian)
      centerX = projectile.getPosition().getX();
      centerY = projectile.getPosition().getY();
    } else if (projectile.isPositionTargeted()) {
      centerX = projectile.getTargetX();
      centerY = projectile.getTargetY();
    } else if (projectile.getTarget() != null) {
      centerX = projectile.getTarget().getPosition().getX();
      centerY = projectile.getTarget().getPosition().getY();
    } else {
      centerX = projectile.getPosition().getX();
      centerY = projectile.getPosition().getY();
    }

    // Always use tight formation around the impact point
    float formationRadius =
        (count > 1) ? SPAWN_ON_IMPACT_FORMATION_RADIUS : projectile.getAoeRadius();

    for (int i = 0; i < count; i++) {
      Vector2 offset =
          FormationLayout.calculateOffset(i, count, formationRadius, stats.getCollisionRadius());
      float spawnX = centerX + offset.getX();
      float spawnY = centerY + offset.getY();

      // Sphere-slide: if spawn position overlaps a building, push radially outward
      // to just outside the building perimeter (like sliding off a sphere)
      for (Entity entity : gameState.getAliveEntities()) {
        if (entity.getMovementType() != MovementType.BUILDING) {
          continue;
        }
        float dx = spawnX - entity.getPosition().getX();
        float dy = spawnY - entity.getPosition().getY();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float minDist = entity.getCollisionRadius() + stats.getCollisionRadius();
        if (dist < minDist) {
          // Push outward from building center
          if (dist > 0.001f) {
            spawnX = entity.getPosition().getX() + (dx / dist) * minDist;
            spawnY = entity.getPosition().getY() + (dy / dist) * minDist;
          } else {
            // Exactly on center -- use the formation offset direction
            float offLen =
                (float) Math.sqrt(offset.getX() * offset.getX() + offset.getY() * offset.getY());
            if (offLen > 0.001f) {
              spawnX = entity.getPosition().getX() + (offset.getX() / offLen) * minDist;
              spawnY = entity.getPosition().getY() + (offset.getY() / offLen) * minDist;
            }
          }
          break; // only slide off one building
        }
      }

      unitSpawner.spawnUnit(spawnX, spawnY, projectile.getTeam(), stats, rarity, level, deployTime);
    }
  }

  /**
   * Chain lightning: find N closest enemies within chainedHitRadius and create sub-projectiles to
   * each. Excludes the primary target.
   */
  private void processChainLightning(Projectile projectile) {
    Entity primaryTarget = projectile.getTarget();
    if (primaryTarget == null) {
      return;
    }

    float chainRadius = projectile.getChainedHitRadius();
    int chainCount = projectile.getChainedHitCount();
    Team team = projectile.getTeam();

    List<Entity> candidates = new ArrayList<>();
    for (Entity e : gameState.getAliveEntities()) {
      if (e == primaryTarget || e.getTeam() == team || !e.isTargetable()) {
        continue;
      }
      if (e instanceof Troop t && t.isInvisible()) {
        continue;
      }
      float distSq = e.getPosition().distanceToSquared(primaryTarget.getPosition());
      float effectiveRadius = chainRadius + e.getCollisionRadius();
      if (distSq <= effectiveRadius * effectiveRadius) {
        candidates.add(e);
      }
    }

    // Sort by squared distance (preserves ordering, avoids sqrt)
    candidates.sort(
        (a, b) -> {
          float da = a.getPosition().distanceToSquared(primaryTarget.getPosition());
          float db = b.getPosition().distanceToSquared(primaryTarget.getPosition());
          return Float.compare(da, db);
        });

    // chainedHitCount includes the primary target, so spawn (count - 1) chain projectiles
    int chainsToSpawn = Math.min(chainCount - 1, candidates.size());
    for (int i = 0; i < chainsToSpawn; i++) {
      Entity chainTarget = candidates.get(i);
      Projectile chain =
          new Projectile(
              projectile.getSource(),
              chainTarget,
              projectile.getDamage(),
              0,
              projectile.getProjectileSpeed(),
              projectile.getEffects(),
              projectile.getCrownTowerDamagePercent());
      chain.setChainOrigin(primaryTarget);
      // Start chain from primary target position so it visually jumps between targets
      chain
          .getPosition()
          .set(primaryTarget.getPosition().getX(), primaryTarget.getPosition().getY());
      gameState.spawnProjectile(chain);
    }
  }

  /** Spawn sub-projectiles on impact (Log rolling projectile, Firecracker explosion fan). */
  private void processSpawnProjectile(Projectile projectile) {
    ProjectileStats spawnStats = projectile.getSpawnProjectile();
    if (spawnStats == null) {
      return;
    }

    float hitX, hitY;
    if (projectile.isPositionTargeted()) {
      hitX = projectile.getTargetX();
      hitY = projectile.getTargetY();
    } else if (projectile.getTarget() != null) {
      hitX = projectile.getTarget().getPosition().getX();
      hitY = projectile.getTarget().getPosition().getY();
    } else {
      hitX = projectile.getPosition().getX();
      hitY = projectile.getPosition().getY();
    }

    // Calculate travel direction from origin to impact
    float dx = hitX - projectile.getOriginX();
    float dy = hitY - projectile.getOriginY();
    float dist = Vector2.distance(projectile.getOriginX(), projectile.getOriginY(), hitX, hitY);
    float dirX = dist > 0 ? dx / dist : 0;
    float dirY = dist > 0 ? dy / dist : 1;

    if (spawnStats.getSpawnCount() > 1) {
      // Fan scatter: spawn multiple piercing sub-projectiles in a cone
      // (e.g. Firecracker shrapnel)
      spawnFanProjectiles(projectile, spawnStats, hitX, hitY, dirX, dirY);
    } else {
      // Single sub-projectile (e.g. Log rolling projectile)
      float range = spawnStats.getProjectileRange() > 0 ? spawnStats.getProjectileRange() : 10f;
      float targetX = hitX + dirX * range;
      float targetY = hitY + dirY * range;

      // Scale sub-projectile damage by spell level/rarity if available
      int subDamage = spawnStats.getDamage();
      if (projectile.getSpellRarity() != null && projectile.getSpellLevel() > 0) {
        subDamage =
            LevelScaling.scaleCard(
                subDamage, projectile.getSpellRarity(), projectile.getSpellLevel());
      }

      // Use projectileRadius for hit detection if available, otherwise fall back to AOE radius
      float hitRadius =
          spawnStats.getProjectileRadius() > 0
              ? spawnStats.getProjectileRadius()
              : spawnStats.getRadius();

      Projectile spawned =
          new Projectile(
              projectile.getTeam(),
              hitX,
              hitY,
              targetX,
              targetY,
              subDamage,
              hitRadius,
              spawnStats.getSpeed(),
              spawnStats.getHitEffects(),
              spawnStats.getCrownTowerDamagePercent());

      // Configure as piercing if it has meaningful projectile range
      if (spawnStats.getProjectileRange() > 0) {
        spawned.configurePiercing(
            dirX, dirY, range, spawnStats.isAoeToGround(), spawnStats.isAoeToAir());
      }

      spawned.setPushback(spawnStats.getPushback());
      spawned.setPushbackAll(spawnStats.isPushbackAll());
      spawned.setMinDistance(spawnStats.getMinDistance());

      // Wire spawn character for piercing expire (e.g. BarbLog spawns Barbarian at end of roll)
      if (spawnStats.getSpawnCharacter() != null) {
        spawned.setSpawnCharacterStats(spawnStats.getSpawnCharacter());
        spawned.setSpawnCharacterCount(spawnStats.getSpawnCharacterCount());
        spawned.setSpawnDeployTime(spawnStats.getSpawnDeployTime());
        if (projectile.getSpellRarity() != null) {
          spawned.setSpawnCharacterRarity(projectile.getSpellRarity());
        }
        spawned.setSpawnCharacterLevel(projectile.getSpellLevel());
      }

      gameState.spawnProjectile(spawned);
    }
  }

  /**
   * Spawns multiple piercing sub-projectiles in a fan pattern from the impact point (e.g.
   * Firecracker shrapnel).
   */
  private void spawnFanProjectiles(
      Projectile parentProjectile,
      ProjectileStats stats,
      float originX,
      float originY,
      float baseDirX,
      float baseDirY) {
    Team team = parentProjectile.getTeam();
    int count = stats.getSpawnCount();
    float range = stats.getProjectileRange() > 0 ? stats.getProjectileRange() : 5f;
    float baseAngle = (float) Math.atan2(baseDirY, baseDirX);

    // Scale shrapnel damage by parent projectile's level/rarity if available
    int scaledDamage = stats.getDamage();
    if (parentProjectile.getSpellRarity() != null && parentProjectile.getSpellLevel() > 0) {
      scaledDamage =
          LevelScaling.scaleCard(
              scaledDamage, parentProjectile.getSpellRarity(), parentProjectile.getSpellLevel());
    }

    // 15 degrees between each shrapnel piece (5 pieces = 60-degree cone)
    float spreadRadians = (float) Math.toRadians(15.0);

    for (int i = 0; i < count; i++) {
      float offsetAngle = (i - count / 2.0f) * spreadRadians;
      float angle = baseAngle + offsetAngle;

      float dirX = (float) Math.cos(angle);
      float dirY = (float) Math.sin(angle);

      float endX = originX + range * dirX;
      float endY = originY + range * dirY;

      Projectile shrapnel =
          new Projectile(
              team,
              originX,
              originY,
              endX,
              endY,
              scaledDamage,
              stats.getRadius(),
              stats.getSpeed(),
              stats.getHitEffects());

      // Configure as piercing so shrapnel travels through enemies
      shrapnel.configurePiercing(dirX, dirY, range, stats.isAoeToGround(), stats.isAoeToAir());

      gameState.spawnProjectile(shrapnel);
    }
  }

  /**
   * Spawns an AreaEffect entity at the projectile's impact point. Used by projectiles that carry a
   * spawnAreaEffect (e.g. Heal Spirit heal zone).
   */
  private void spawnAreaEffectOnImpact(Projectile projectile) {
    AreaEffectStats stats = projectile.getSpawnAreaEffect();

    float centerX, centerY;
    if (projectile.isPositionTargeted()) {
      centerX = projectile.getTargetX();
      centerY = projectile.getTargetY();
    } else if (projectile.getTarget() != null) {
      centerX = projectile.getTarget().getPosition().getX();
      centerY = projectile.getTarget().getPosition().getY();
    } else {
      return;
    }

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName() != null ? stats.getName() : "SpawnedAreaEffect")
            .team(projectile.getTeam())
            .position(new Position(centerX, centerY))
            .stats(stats)
            .remainingLifetime(stats.getLifeDuration())
            .build();

    gameState.spawnEntity(effect);
  }
}
