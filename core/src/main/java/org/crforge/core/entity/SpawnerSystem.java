package org.crforge.core.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.Setter;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ElixirCollectorComponent;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Match;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

public class SpawnerSystem {

  private final GameState gameState;
  private final AoeDamageService aoeDamageService;
  private final List<PendingDeathSpawn> pendingDeathSpawns = new ArrayList<>();

  /**
   * -- SETTER -- Sets the match reference, needed for elixir grant on death (e.g. Elixir Golem).
   */
  @Setter private Match match;

  public SpawnerSystem(GameState gameState, AoeDamageService aoeDamageService) {
    this.gameState = gameState;
    this.aoeDamageService = aoeDamageService;
  }

  /** Legacy constructor for tests that don't need death damage. */
  public SpawnerSystem(GameState gameState) {
    this(gameState, null);
  }

  /**
   * Spawns a unit at a specific position. Used by CombatSystem for projectile spawn-on-impact (e.g.
   * PhoenixFireball spawns PhoenixEgg, GoblinBarrel spawns Goblins).
   */
  public void spawnUnit(
      float x, float y, Team team, TroopStats stats, Rarity rarity, int level, float deployTime) {
    doSpawn(new Position(x, y), new Vector2(0, 0), team, stats, rarity, level, deployTime, false);
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
        Vector2 offset;
        if (spawner.isSpawnOnAggro()) {
          // Spawn adjacent to building, toward the nearest enemy
          Entity nearestEnemy = findNearestEnemy(entity, spawner.getAggroDetectionRange());
          offset = calculateAggroSpawnOffset(entity, nearestEnemy);
        } else {
          int spawnIndex = spawner.getLastSpawnIndex();
          int total = spawner.getUnitsPerWave();
          float formationRadius = spawner.getFormationRadius();
          float collisionRadius = spawner.getSpawnStats().getCollisionRadius();
          offset =
              FormationLayout.calculateOffset(spawnIndex, total, formationRadius, collisionRadius);
        }

        // Propagate clone status: if parent is a clone, children are clones too
        boolean parentIsClone = entity instanceof Troop troop && troop.isClone();
        doSpawn(
            entity.getPosition(),
            offset,
            entity.getTeam(),
            spawner.getSpawnStats(),
            spawner.getRarity(),
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

    // Process pending delayed death spawns
    if (!pendingDeathSpawns.isEmpty()) {
      Iterator<PendingDeathSpawn> it = pendingDeathSpawns.iterator();
      while (it.hasNext()) {
        PendingDeathSpawn pending = it.next();
        pending.timer -= deltaTime;
        if (pending.timer <= 0) {
          doSpawn(
              new Position(pending.x, pending.y),
              new Vector2(pending.offsetX, pending.offsetY),
              pending.team,
              pending.stats,
              pending.rarity,
              pending.level,
              pending.deployTime,
              pending.isClone);
          it.remove();
        }
      }
    }
  }

  /**
   * Returns true if any targetable enemy entity is within the given range of the source entity.
   * Used for aggro-gated spawning (e.g. GoblinHut_Rework only spawns when enemies are nearby).
   */
  private boolean hasEnemyInRange(Entity source, float range) {
    return findNearestEnemy(source, range) != null;
  }

  /**
   * Returns the nearest targetable enemy entity within the given range, or null if none found. Used
   * for aggro-gated spawning to determine both presence and direction of nearby enemies.
   */
  private Entity findNearestEnemy(Entity source, float range) {
    float rangeSq = range * range;
    Team enemyTeam = source.getTeam().opposite();
    Entity nearest = null;
    float nearestDistSq = Float.MAX_VALUE;
    for (Entity other : gameState.getAliveEntities()) {
      if (other.getTeam() != enemyTeam || !other.isTargetable()) {
        continue;
      }
      EntityType type = other.getEntityType();
      if (type != EntityType.TROOP && type != EntityType.BUILDING && type != EntityType.TOWER) {
        continue;
      }
      float distSq = source.getPosition().distanceToSquared(other.getPosition());
      if (distSq <= rangeSq && distSq < nearestDistSq) {
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
  private Vector2 calculateAggroSpawnOffset(Entity building, Entity target) {
    if (target == null) {
      return new Vector2(0, 0);
    }
    float dx = target.getPosition().getX() - building.getPosition().getX();
    float dy = target.getPosition().getY() - building.getPosition().getY();
    float dist = (float) Math.sqrt(dx * dx + dy * dy);
    if (dist < 0.001f) {
      return new Vector2(0, 0);
    }
    // Offset by building's collision radius so the unit spawns at the building's edge
    float spawnDist = building.getCollisionRadius();
    return new Vector2((dx / dist) * spawnDist, (dy / dist) * spawnDist);
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
    // Handle manaOnDeath for elixir collector buildings (grants elixir to owner on death)
    if (entity instanceof Building building && building.getElixirCollector() != null) {
      ElixirCollectorComponent collector = building.getElixirCollector();
      if (collector.getManaOnDeath() > 0 && match != null) {
        for (Player player : match.getPlayers(entity.getTeam())) {
          player.getElixir().add(collector.getManaOnDeath());
        }
      }
    }

    SpawnerComponent spawner = entity.getSpawner();
    // Propagate clone status: if dying entity is a clone, children are clones too
    boolean parentIsClone = entity instanceof Troop troop && troop.isClone();

    if (spawner != null) {
      // 1. Apply death damage AOE (e.g. Golem, Ice Golem explode on death)
      if (spawner.getDeathDamage() > 0 && aoeDamageService != null) {
        aoeDamageService.applySpellDamage(
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
          // Use explicit relative offsets when specified, otherwise FormationLayout
          Vector2 offset;
          if (entry.relativeX() != null) {
            offset =
                new Vector2(entry.relativeX(), entry.relativeY() != null ? entry.relativeY() : 0f);
          } else {
            offset =
                FormationLayout.calculateOffset(
                    i, entry.count(), entry.radius(), entry.stats().getCollisionRadius());
          }

          if (entry.spawnDelay() > 0) {
            // Queue for delayed spawning
            pendingDeathSpawns.add(
                new PendingDeathSpawn(
                    entity.getPosition().getX(),
                    entity.getPosition().getY(),
                    offset.getX(),
                    offset.getY(),
                    entity.getTeam(),
                    entry.stats(),
                    spawner.getRarity(),
                    spawner.getLevel(),
                    entry.deployTime(),
                    parentIsClone,
                    entry.spawnDelay()));
          } else {
            doSpawn(
                entity.getPosition(),
                offset,
                entity.getTeam(),
                entry.stats(),
                spawner.getRarity(),
                spawner.getLevel(),
                entry.deployTime(),
                parentIsClone);
          }
        }
      }

      // 3. Spawn death area effect (e.g. RageBarbarianBottle drops Rage zone)
      // Skip dummy AEOs that exist only as internal triggers with no gameplay effect
      if (spawner.getDeathAreaEffect() != null && !spawner.getDeathAreaEffect().isDummy()) {
        spawnDeathAreaEffect(entity, spawner);
      }

      // 4. Grant elixir to opponent on death (e.g. Elixir Golem)
      if (spawner.getManaOnDeathForOpponent() > 0 && match != null) {
        float elixirAmount = spawner.getManaOnDeathForOpponent() / 1000f;
        Team opponentTeam = entity.getTeam().opposite();
        for (Player player : match.getPlayers(opponentTeam)) {
          player.getElixir().add(elixirAmount);
        }
      }

      // 5. Fire death spawn projectile (e.g. Phoenix -> PhoenixFireball)
      if (spawner.getDeathSpawnProjectile() != null) {
        fireDeathProjectile(entity, spawner);
      }

      // 6. Fallback: legacy death spawn via deathSpawnCount + spawnStats
      if (spawner.getDeathSpawns().isEmpty() && spawner.getDeathSpawnCount() > 0) {
        for (int i = 0; i < spawner.getDeathSpawnCount(); i++) {
          Vector2 offset =
              FormationLayout.calculateOffset(
                  i,
                  spawner.getDeathSpawnCount(),
                  spawner.getFormationRadius(),
                  spawner.getSpawnStats().getCollisionRadius());
          doSpawn(
              entity.getPosition(),
              offset,
              entity.getTeam(),
              spawner.getSpawnStats(),
              spawner.getRarity(),
              spawner.getLevel(),
              0f,
              parentIsClone);
        }
      }
    }

    // 5. Handle Effects (e.g. Mother Witch CURSE -> Cursed Hog)
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
    doSpawn(victim.getPosition(), new Vector2(0, 0), ownerTeam, stats, Rarity.COMMON, 1, 0f, false);
  }

  /**
   * Spawns an AreaEffect entity at the dying entity's position. Used for death area effects like
   * the RageBarbarianBottle dropping a Rage zone. Follows the same pattern as
   * DeploymentSystem.deployAreaEffect().
   */
  private void spawnDeathAreaEffect(Entity entity, SpawnerComponent spawner) {
    AreaEffectStats stats = spawner.getDeathAreaEffect();
    int scaledDamage =
        stats.getDamage() > 0
            ? LevelScaling.scaleCard(stats.getDamage(), spawner.getRarity(), spawner.getLevel())
            : 0;
    int resolvedCtdp = stats.getCrownTowerDamagePercent();
    int buildingDmgPct = 0;

    // Resolve values from BuffDefinition if the area effect has a buff
    BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
    if (buffDef != null) {
      if (scaledDamage == 0 && buffDef.getDamagePerSecond() > 0) {
        float hitSpeed = stats.getHitSpeed() > 0 ? stats.getHitSpeed() : 1.0f;
        int baseDamage = Math.round(buffDef.getDamagePerSecond() * hitSpeed);
        scaledDamage = LevelScaling.scaleCard(baseDamage, spawner.getRarity(), spawner.getLevel());
      }
      if (resolvedCtdp == 0 && buffDef.getCrownTowerDamagePercent() != 0) {
        resolvedCtdp = buffDef.getCrownTowerDamagePercent();
      }
      if (buffDef.getBuildingDamagePercent() != 0) {
        buildingDmgPct = buffDef.getBuildingDamagePercent();
      }
    }

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName())
            .team(entity.getTeam())
            .position(new Position(entity.getPosition().getX(), entity.getPosition().getY()))
            .stats(stats)
            .scaledDamage(scaledDamage)
            .resolvedCrownTowerDamagePercent(resolvedCtdp)
            .buildingDamagePercent(buildingDmgPct)
            .remainingLifetime(stats.getLifeDuration())
            .build();

    gameState.spawnEntity(effect);
  }

  private static final float KNOCKBACK_DURATION = 0.5f;
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  /**
   * Applies knockback to nearby enemies from a death explosion. Buildings and entities with
   * ignorePushback are immune.
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

  /**
   * Fires a death spawn projectile at the dying entity's position (e.g. Phoenix ->
   * PhoenixFireball). The projectile deals AOE damage and may spawn a character on impact.
   */
  private void fireDeathProjectile(Entity entity, SpawnerComponent spawner) {
    ProjectileStats projStats = spawner.getDeathSpawnProjectile();
    float x = entity.getPosition().getX();
    float y = entity.getPosition().getY();

    // Damage is already level-scaled when stored in SpawnerComponent (by doSpawn/DeploymentSystem)
    int damage = projStats.getDamage();

    // Position-targeted projectile at death position (arrives instantly since start == dest)
    Projectile projectile =
        new Projectile(
            entity.getTeam(),
            x,
            y,
            x,
            y,
            damage,
            projStats.getRadius(),
            projStats.getSpeed(),
            projStats.getHitEffects(),
            projStats.getCrownTowerDamagePercent());
    projectile.setPushback(projStats.getPushback());
    projectile.setPushbackAll(projStats.isPushbackAll());
    projectile.setAoeToGround(projStats.isAoeToGround());
    projectile.setAoeToAir(projStats.isAoeToAir());

    // Wire spawn character info so CombatSystem can spawn it on impact
    if (projStats.getSpawnCharacter() != null) {
      projectile.setSpawnCharacterStats(projStats.getSpawnCharacter());
      projectile.setSpawnCharacterCount(projStats.getSpawnCharacterCount());
      projectile.setSpawnCharacterRarity(spawner.getRarity());
      projectile.setSpawnCharacterLevel(spawner.getLevel());
    }

    gameState.spawnProjectile(projectile);
  }

  private void doSpawn(
      Position origin,
      Vector2 offset,
      Team team,
      TroopStats stats,
      Rarity rarity,
      int level,
      float deathSpawnDeployTime,
      boolean asClone) {
    float x = origin.getX() + offset.getX();
    float y = origin.getY() + offset.getY();

    // Bomb entities (health=0) survive their deploy phase with 1 HP, then self-destruct
    boolean isBomb = stats.getHealth() <= 0;
    int baseHp = isBomb ? 1 : stats.getHealth();

    int scaledHp = LevelScaling.scaleCard(baseHp, rarity, level);
    int scaledDamage = LevelScaling.scaleCard(stats.getDamage(), rarity, level);
    int scaledShield =
        stats.getShieldHitpoints() > 0
            ? LevelScaling.scaleCard(stats.getShieldHitpoints(), rarity, level)
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
              .accumulatedLoadTime(initialLoad)
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
          stats.getDeathDamage() > 0
              ? LevelScaling.scaleCard(stats.getDeathDamage(), rarity, level)
              : 0;

      // Scale death spawn projectile damage
      ProjectileStats deathProjStats = null;
      if (stats.getDeathSpawnProjectile() != null) {
        deathProjStats =
            stats
                .getDeathSpawnProjectile()
                .withDamage(
                    LevelScaling.scaleCard(
                        stats.getDeathSpawnProjectile().getDamage(), rarity, level));
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
              .rarity(rarity)
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

  /** Pending delayed death spawn entry. Stores all data needed to call doSpawn() after a delay. */
  private static class PendingDeathSpawn {
    final float x;
    final float y;
    final float offsetX;
    final float offsetY;
    final Team team;
    final TroopStats stats;
    final Rarity rarity;
    final int level;
    final float deployTime;
    final boolean isClone;
    float timer;

    PendingDeathSpawn(
        float x,
        float y,
        float offsetX,
        float offsetY,
        Team team,
        TroopStats stats,
        Rarity rarity,
        int level,
        float deployTime,
        boolean isClone,
        float timer) {
      this.x = x;
      this.y = y;
      this.offsetX = offsetX;
      this.offsetY = offsetY;
      this.team = team;
      this.stats = stats;
      this.rarity = rarity;
      this.level = level;
      this.deployTime = deployTime;
      this.isClone = isClone;
      this.timer = timer;
    }
  }
}
