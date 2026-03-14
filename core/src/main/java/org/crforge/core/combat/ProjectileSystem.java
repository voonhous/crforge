package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
import lombok.Setter;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
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
 * Handles projectile lifecycle: movement, hit detection, chain lightning, scatter, piercing,
 * knockback, and spawn-on-impact mechanics.
 */
public class ProjectileSystem {

  // Knockback: 15 active frames at 30fps = 0.5s displacement duration
  static final float KNOCKBACK_DURATION = 0.5f;
  // Speed calculation base: distance / 1.0s (reference JS uses 30 frames for speed calc)
  static final float KNOCKBACK_MAX_TIME = 1.0f;

  // Tight formation radius for multi-unit spawn-on-impact (matches 3-Minion deploy pattern).
  // Physics collision pushes units out of overlapping buildings, creating the correct spread.
  private static final float SPAWN_ON_IMPACT_FORMATION_RADIUS = 0.577f;

  private final GameState gameState;
  private final AoeDamageService aoeDamageService;

  /**
   * -- SETTER -- Sets the UnitSpawner callback for spawn-on-impact projectiles (e.g.
   * PhoenixFireball).
   */
  @Setter private UnitSpawner unitSpawner;

  public ProjectileSystem(GameState gameState, AoeDamageService aoeDamageService) {
    this.gameState = gameState;
    this.aoeDamageService = aoeDamageService;
  }

  /** Update and process all projectiles. Called each tick. */
  public void update(float deltaTime) {
    List<Projectile> projectiles = gameState.getProjectiles();
    // Use index-based loop: onProjectileHit may spawn new projectiles (chain lightning,
    // spawn-on-impact) which append to the list. We only process projectiles that existed
    // at the start of this tick by capturing the initial size.
    int count = projectiles.size();

    for (int i = 0; i < count; i++) {
      Projectile projectile = projectiles.get(i);
      boolean hit = projectile.update(deltaTime);

      if (hit) {
        onProjectileHit(projectile);
      }

      // Piercing projectiles check for hits every tick while still active
      if (projectile.isPiercing() && projectile.isActive()) {
        processPiercingHits(projectile);
      }
    }

    // Re-enable combat on source entities whose returning projectiles have deactivated
    projectiles.removeIf(
        p -> {
          if (!p.isActive()) {
            if (p.isReturningEnabled() && p.getSourceEntity() != null) {
              Entity source = p.getSourceEntity();
              if (source.isAlive() && source.getCombat() != null) {
                source.getCombat().setCombatDisabled(ModifierSource.RETURNING_PROJECTILE, false);
              }
            }
            return true;
          }
          return false;
        });
  }

  /**
   * Creates a projectile for a ranged attack, resolving stats from the combat component's
   * ProjectileStats with fallback to combat-level values.
   */
  Projectile createAttackProjectile(Entity attacker, Entity target, int damage, Combat combat) {
    ProjectileStats stats = combat.getProjectileStats();

    float speed = (stats != null) ? stats.getSpeed() : 0;
    float aoeRadius = (stats != null) ? stats.getRadius() : combat.getAoeRadius();
    List<EffectStats> effects =
        new ArrayList<>((stats != null) ? stats.getHitEffects() : combat.getHitEffects());

    // Merge buffOnDamage as a post-damage effect if no projectile-level post-damage effect exists
    boolean hasPostDamageEffect = false;
    for (EffectStats e : effects) {
      if (e.isApplyAfterDamage()) {
        hasPostDamageEffect = true;
        break;
      }
    }
    if (!hasPostDamageEffect && combat.getBuffOnDamage() != null) {
      EffectStats bod = combat.getBuffOnDamage();
      effects.add(
          EffectStats.builder()
              .type(bod.getType())
              .duration(bod.getDuration())
              .buffName(bod.getBuffName())
              .applyAfterDamage(true)
              .build());
    }

    Projectile projectile =
        new Projectile(
            attacker,
            target,
            damage,
            aoeRadius,
            speed,
            effects,
            combat.getCrownTowerDamagePercent());

    // Wire advanced projectile features from stats
    if (stats != null) {
      projectile.setChainedHitRadius(stats.getChainedHitRadius());
      projectile.setChainedHitCount(stats.getChainedHitCount());
      projectile.setSpawnProjectile(stats.getSpawnProjectile());
      projectile.setPushback(stats.getPushback());
      projectile.setPushbackAll(stats.isPushbackAll());
      projectile.setSpawnAreaEffect(stats.getSpawnAreaEffect());

      // Returning projectiles (e.g. Executioner axe) are piercing: they travel out, reverse,
      // and return to the source. Configure piercing + returning.
      if (stats.isReturning() && stats.getProjectileRange() >= 1.0f) {
        float dx = target.getPosition().getX() - attacker.getPosition().getX();
        float dy = target.getPosition().getY() - attacker.getPosition().getY();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float dirX = dist > 0.001f ? dx / dist : 0f;
        float dirY = dist > 0.001f ? dy / dist : 1f;
        projectile.configurePiercing(
            dirX, dirY, stats.getProjectileRange(), stats.isAoeToGround(), stats.isAoeToAir());
        projectile.configureReturning(attacker);
      } else if (!stats.isHoming() && stats.getProjectileRange() >= 1.0f) {
        // Non-homing projectiles with meaningful projectileRange travel in a line,
        // hitting all entities along their path (e.g. Bowler boulder, Magic Archer arrow).
        // Threshold >= 1.0 excludes sentinel values like 0.001 (WallbreakerProjectile).
        float dx = target.getPosition().getX() - attacker.getPosition().getX();
        float dy = target.getPosition().getY() - attacker.getPosition().getY();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float dirX = dist > 0.001f ? dx / dist : 0f;
        float dirY = dist > 0.001f ? dy / dist : 1f;
        projectile.configurePiercing(
            dirX, dirY, stats.getProjectileRange(), stats.isAoeToGround(), stats.isAoeToAir());
      } else if (!stats.isHoming()) {
        // Non-homing projectiles fly to the target's position at fire time
        projectile.setHoming(false);
      }
    }

    return projectile;
  }

  /**
   * Fires scatter projectiles in a fan pattern (Hunter shotgun). Each pellet is a piercing
   * projectile that travels in a fixed direction for the projectile's range.
   *
   * @param attacker the attacking entity
   * @param target the primary target (used to calculate base angle)
   * @param damage per-pellet damage
   * @param combat the attacker's combat component
   */
  void fireScatterProjectiles(Entity attacker, Entity target, int damage, Combat combat) {
    ProjectileStats stats = combat.getProjectileStats();
    int count = combat.getMultipleProjectiles();

    float attackerX = attacker.getPosition().getX();
    float attackerY = attacker.getPosition().getY();
    float targetX = target.getPosition().getX();
    float targetY = target.getPosition().getY();

    // Base angle toward the target
    float baseAngle = (float) Math.atan2(targetY - attackerY, targetX - attackerX);

    // Spread angle between pellets (10 degrees, matching JS reference)
    float spreadDegrees = 10f;
    float spreadRadians = (float) Math.toRadians(spreadDegrees);

    // Start position: 0.65 tiles ahead of attacker in the base direction
    float startOffsetDist = 0.65f;
    float startX = attackerX + startOffsetDist * (float) Math.cos(baseAngle);
    float startY = attackerY + startOffsetDist * (float) Math.sin(baseAngle);

    float range = stats.getProjectileRange() > 0 ? stats.getProjectileRange() : 6.5f;

    List<EffectStats> effects = new ArrayList<>(stats.getHitEffects());

    for (int i = 0; i < count; i++) {
      // Offset angle: centered around base angle
      float offsetAngle = (i - count / 2.0f) * spreadRadians;
      float pelletAngle = baseAngle + offsetAngle;

      // Direction for this pellet
      float dirX = (float) Math.cos(pelletAngle);
      float dirY = (float) Math.sin(pelletAngle);

      // End position: range tiles from start in the pellet direction
      float endX = startX + range * dirX;
      float endY = startY + range * dirY;

      Projectile pellet =
          new Projectile(
              attacker.getTeam(),
              startX,
              startY,
              endX,
              endY,
              damage,
              stats.getRadius(),
              stats.getSpeed(),
              effects,
              combat.getCrownTowerDamagePercent());

      // Configure as piercing so pellets travel through enemies
      pellet.configurePiercing(dirX, dirY, range, stats.isAoeToGround(), stats.isAoeToAir());
      pellet.setCheckCollisions(stats.isCheckCollisions());

      gameState.spawnProjectile(pellet);
    }
  }

  private void onProjectileHit(Projectile projectile) {
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
        dealAoeDamage(
            projectile.getSource(),
            projectile.getTarget(),
            baseDamage,
            projectile.getAoeRadius(),
            projectile.getEffects(),
            ctdp);
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
      applyKnockback(projectile);
    }

    // Reflect: if a projectile hits a REFLECT target and the source is within reflect radius, zap
    // them
    if (!projectile.isPositionTargeted()) {
      Entity target = projectile.getTarget();
      if (target instanceof Troop reflector) {
        int reflectDmg = AbilitySystem.getReflectDamage(reflector);
        if (reflectDmg > 0 && reflector.getAbility().getData() instanceof ReflectAbility reflect) {
          Entity source = projectile.getSource();
          if (source != null && source.isAlive()) {
            float dist = source.getPosition().distanceTo(reflector.getPosition());
            float effectiveRadius = reflect.reflectRadius() + source.getCollisionRadius();
            if (dist <= effectiveRadius) {
              applyReflectDamage(reflector, source, reflectDmg);
            }
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
      // Fan scatter: spawn multiple piercing sub-projectiles in a cone (e.g. Firecracker shrapnel)
      spawnFanProjectiles(projectile.getTeam(), spawnStats, hitX, hitY, dirX, dirY);
    } else {
      // Single sub-projectile (e.g. Log rolling projectile)
      float range = spawnStats.getProjectileRange() > 0 ? spawnStats.getProjectileRange() : 10f;
      float targetX = hitX + dirX * range;
      float targetY = hitY + dirY * range;

      Projectile spawned =
          new Projectile(
              projectile.getTeam(),
              hitX,
              hitY,
              targetX,
              targetY,
              spawnStats.getDamage(),
              spawnStats.getRadius(),
              spawnStats.getSpeed(),
              spawnStats.getHitEffects());
      gameState.spawnProjectile(spawned);
    }
  }

  /**
   * Spawns multiple piercing sub-projectiles in a fan pattern from the impact point (e.g.
   * Firecracker shrapnel). Each shrapnel piece travels in a fixed direction, piercing through all
   * enemies in its path.
   */
  private void spawnFanProjectiles(
      Team team,
      ProjectileStats stats,
      float originX,
      float originY,
      float baseDirX,
      float baseDirY) {
    int count = stats.getSpawnCount();
    float range = stats.getProjectileRange() > 0 ? stats.getProjectileRange() : 5f;
    float baseAngle = (float) Math.atan2(baseDirY, baseDirX);

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
              stats.getDamage(),
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

  /**
   * Spawns characters at the projectile's impact point with formation spread. Used by projectiles
   * that carry a spawnCharacter (e.g. PhoenixFireball spawns PhoenixEgg, GoblinBarrel spawns 3
   * Goblins in a triangle).
   *
   * <p>Uses sphere-slide placement: units spawn in a tight formation around the impact point, then
   * any unit overlapping a building is pushed radially outward from the building center to just
   * outside its perimeter. This naturally produces 120-degree spread for center hits, asymmetric
   * clustering for offset hits, and unchanged tight formation in open space.
   */
  private void spawnCharacterOnImpact(Projectile projectile) {
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
    if (projectile.isPositionTargeted()) {
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
   * Applies knockback displacement to entities hit by a projectile. AOE projectiles push all
   * enemies within radius; non-AOE pushes only the direct target. Buildings and entities with
   * ignorePushback are immune.
   */
  private void applyKnockback(Projectile projectile) {
    float pushback = projectile.getPushback();

    // Determine impact center
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

    float radius = projectile.getAoeRadius();
    Team projectileTeam = projectile.getTeam();
    Team enemyTeam = projectileTeam.opposite();

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

      // AOE: check radius. Non-AOE (pushbackAll=false): only the direct target.
      if (radius > 0 || projectile.isPushbackAll()) {
        float distSq = entity.getPosition().distanceToSquared(centerX, centerY);
        float effectiveRadius = radius + entity.getCollisionRadius();
        if (radius > 0 && distSq > effectiveRadius * effectiveRadius) {
          continue;
        }
      } else if (projectile.getTarget() != entity) {
        continue;
      }

      // Direction from impact center to entity
      float dx = entity.getPosition().getX() - centerX;
      float dy = entity.getPosition().getY() - centerY;
      float dist = (float) Math.sqrt(dx * dx + dy * dy);
      float dirX = dist > 0.001f ? dx / dist : 0f;
      float dirY = dist > 0.001f ? dy / dist : 1f;

      movement.startKnockback(dirX, dirY, pushback, KNOCKBACK_DURATION, KNOCKBACK_MAX_TIME);
    }
  }

  /**
   * Processes per-tick hit detection for a piercing projectile. Checks all alive enemy entities
   * within the projectile's AOE radius, applies damage and effects, and triggers directional
   * knockback for each newly-hit entity.
   */
  private void processPiercingHits(Projectile projectile) {
    Team enemyTeam = projectile.getTeam().opposite();
    float projX = projectile.getPosition().getX();
    float projY = projectile.getPosition().getY();
    float aoeRadius = projectile.getAoeRadius();
    int baseDamage = projectile.getDamage();
    int ctdp = projectile.getCrownTowerDamagePercent();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam || !entity.isTargetable()) {
        continue;
      }
      if (entity instanceof Troop t && t.isInvisible()) {
        continue;
      }
      if (projectile.hasHitEntity(entity.getId())) {
        continue;
      }
      if (!canPiercingHit(projectile, entity)) {
        continue;
      }

      float distSq = entity.getPosition().distanceToSquared(projX, projY);
      float effectiveRadius = aoeRadius + entity.getCollisionRadius();
      if (distSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      projectile.recordHitEntity(entity.getId());

      int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, entity, ctdp);
      aoeDamageService.applyEffects(
          entity, aoeDamageService.filterEffects(projectile.getEffects(), false));
      aoeDamageService.dealDamage(entity, effectiveDamage);
      aoeDamageService.applyEffects(
          entity, aoeDamageService.filterEffects(projectile.getEffects(), true));

      // Apply directional knockback along the projectile's travel direction
      if (projectile.getPushback() > 0) {
        applyDirectionalKnockback(projectile, entity);
      }

      // checkCollisions: projectile stops on first hit (e.g. Hunter pellets)
      if (projectile.isCheckCollisions()) {
        projectile.deactivate();
        break;
      }
    }
  }

  /**
   * Returns true if the entity's movement type is compatible with the piercing projectile's
   * aoeToGround/aoeToAir targeting flags.
   */
  private boolean canPiercingHit(Projectile projectile, Entity entity) {
    MovementType mt = entity.getMovementType();
    if (mt == MovementType.AIR) {
      return projectile.isAoeToAir();
    }
    // GROUND and BUILDING are ground-level targets
    return projectile.isAoeToGround();
  }

  /**
   * Applies knockback to an entity in the piercing projectile's travel direction, rather than
   * radially from the impact point. Buildings and ignorePushback entities are immune.
   */
  private void applyDirectionalKnockback(Projectile projectile, Entity entity) {
    if (entity.getMovementType() == MovementType.BUILDING) {
      return;
    }
    Movement movement = entity.getMovement();
    if (movement == null || movement.isIgnorePushback()) {
      return;
    }
    movement.startKnockback(
        projectile.getPiercingDirX(),
        projectile.getPiercingDirY(),
        projectile.getPushback(),
        KNOCKBACK_DURATION,
        KNOCKBACK_MAX_TIME);
  }

  private void applyReflectDamage(Troop reflector, Entity attacker, int reflectDamage) {
    ReflectAbility reflect = (ReflectAbility) reflector.getAbility().getData();
    int effectiveDamage =
        DamageUtil.adjustForCrownTower(
            reflectDamage, attacker, reflect.reflectCrownTowerDamagePercent());
    aoeDamageService.dealDamage(attacker, effectiveDamage);

    // Apply reflect buff (e.g. ZapFreeze stun) to attacker
    if (reflect.reflectBuff() != null && reflect.reflectBuffDuration() > 0) {
      EffectStats reflectEffect =
          EffectStats.builder()
              .type(reflect.reflectBuff())
              .duration(reflect.reflectBuffDuration())
              .buffName(reflect.reflectBuffName())
              .build();
      aoeDamageService.applyEffects(attacker, List.of(reflectEffect));
    }
  }

  private void dealAoeDamage(
      Entity source,
      Entity primaryTarget,
      int damage,
      float radius,
      List<EffectStats> effects,
      int crownTowerDamagePercent) {
    if (primaryTarget == null) {
      return;
    }
    aoeDamageService.applySpellDamage(
        source.getTeam(),
        primaryTarget.getPosition().getX(),
        primaryTarget.getPosition().getY(),
        damage,
        radius,
        effects,
        crownTowerDamagePercent);
  }
}
