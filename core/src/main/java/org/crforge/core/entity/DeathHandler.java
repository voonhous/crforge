package org.crforge.core.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.Setter;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.component.ElixirCollectorComponent;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
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

/**
 * Handles all death-related mechanics: death damage, knockback, death spawns (immediate and
 * delayed), death area effects, death projectiles, elixir grants, and curse spawns.
 */
class DeathHandler {

  private static final float KNOCKBACK_DURATION = 0.5f;
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  private final GameState gameState;
  private final AoeDamageService aoeDamageService;
  private final SpawnFactory spawnFactory;
  private final List<PendingDeathSpawn> pendingDeathSpawns = new ArrayList<>();

  /**
   * -- SETTER -- Sets the match reference, needed for elixir grant on death (e.g. Elixir Golem).
   */
  @Setter private Match match;

  DeathHandler(GameState gameState, AoeDamageService aoeDamageService, SpawnFactory spawnFactory) {
    this.gameState = gameState;
    this.aoeDamageService = aoeDamageService;
    this.spawnFactory = spawnFactory;
  }

  /** Called by GameState.processDeaths() via SpawnerSystem. */
  void onDeath(Entity entity) {
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
            spawnFactory.doSpawn(
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
          spawnFactory.doSpawn(
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

    // Handle Effects (e.g. Mother Witch CURSE -> Cursed Hog)
    for (AppliedEffect effect : entity.getAppliedEffects()) {
      if (effect.getType() == StatusEffectType.CURSE && effect.getSpawnSpecies() != null) {
        // Spawn the unit for the OPPONENT of the dying unit
        // (i.e. the team of the Mother Witch who applied the curse)
        spawnEffectUnit(entity, effect.getSpawnSpecies(), entity.getTeam().opposite());
      }
    }
  }

  /** Process pending delayed death spawns. Called by SpawnerSystem.update() each tick. */
  void processDelayedSpawns(float deltaTime) {
    if (pendingDeathSpawns.isEmpty()) {
      return;
    }
    Iterator<PendingDeathSpawn> it = pendingDeathSpawns.iterator();
    while (it.hasNext()) {
      PendingDeathSpawn pending = it.next();
      pending.timer -= deltaTime;
      if (pending.timer <= 0) {
        spawnFactory.doSpawn(
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

  private void spawnEffectUnit(Entity victim, TroopStats stats, Team ownerTeam) {
    if (stats == null) {
      return;
    }
    // Effect spawns (like Cursed Hogs) appear at the victim's location with no offset.
    // Uses level 1 / Common defaults -- Curse spawns need complex mechanics for proper scaling.
    spawnFactory.doSpawn(
        victim.getPosition(), new Vector2(0, 0), ownerTeam, stats, Rarity.COMMON, 1, 0f, false);
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

    // Resolve values from BuffDefinitions in the buff applications
    for (BuffApplication ba : stats.getBuffApplications()) {
      BuffDefinition buffDef = BuffRegistry.get(ba.buffName());
      if (buffDef == null) {
        continue;
      }
      if (scaledDamage == 0 && buffDef.getDamagePerSecond() > 0) {
        float hitSpeed = stats.getHitSpeed() > 0 ? stats.getHitSpeed() : 1.0f;
        int baseDamage = Math.round(buffDef.getDamagePerSecond() * hitSpeed);
        scaledDamage = LevelScaling.scaleCard(baseDamage, spawner.getRarity(), spawner.getLevel());
      }
      if (resolvedCtdp == 0 && buffDef.getCrownTowerDamagePercent() != 0) {
        resolvedCtdp = buffDef.getCrownTowerDamagePercent();
      }
      if (buildingDmgPct == 0 && buffDef.getBuildingDamagePercent() != 0) {
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

  /** Pending delayed death spawn entry. Stores all data needed to call doSpawn() after a delay. */
  static class PendingDeathSpawn {
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
