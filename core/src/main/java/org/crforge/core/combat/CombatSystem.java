package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.ability.AbilityData;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.Vector2;

/**
 * Handles attack execution, damage dealing, and projectile management.
 */
public class CombatSystem {

  private final GameState gameState;

  public CombatSystem(GameState gameState) {
    this.gameState = gameState;
  }

  /**
   * Process combat for all entities. Called each tick.
   */
  public void update(float deltaTime) {
    // Process attacks for ANY entity with a combat component (Troop or Building)
    for (Entity entity : gameState.getAliveEntities()) {
      processEntityCombat(entity);
    }

    // Update and process projectiles
    updateProjectiles(deltaTime);
  }

  private void processEntityCombat(Entity entity) {
    // Inactive towers or waking up towers cannot attack
    if (entity instanceof Tower tower) {
      if (!tower.isActive() || tower.isWakingUp()) {
        return;
      }
    }

    Combat combat = entity.getCombat();

    // Entity cannot fight (e.g., Elixir Collector)
    if (combat == null) {
      return;
    }

    // Troops cannot attack while deploying
    if (entity instanceof Troop troop && troop.isDeploying()) {
      return;
    }

    if (!combat.hasTarget()) {
      // If we don't have a target, ensure we aren't stuck in attack state
      if (combat.isAttacking()) {
        combat.setAttacking(false);
        combat.setCurrentWindup(0);
      }
      return;
    }

    Entity target = combat.getCurrentTarget();

    // Range Check
    if (!isInAttackRange(entity, target, combat)) {
      // If out of range, cancel any ongoing attack
      if (combat.isAttacking()) {
        combat.setAttacking(false);
        combat.setCurrentWindup(0);
      }
      return;
    }

    // Check Cooldown
    if (!combat.canAttack()) {
      return;
    }

    // Charge impact: skip windup and deal damage instantly on contact
    if (entity instanceof Troop troop && troop.getAbility() != null
        && troop.getAbility().isCharged()) {
      combat.startAttackSequence();
      executeAttack(entity, target, combat);
      return;
    }

    // Start Attack if ready
    if (!combat.isAttacking()) {
      combat.startAttackSequence();
    }

    // Check Windup
    if (combat.isWindingUp()) {
      return;
    }

    // Execute Attack (Windup complete)
    executeAttack(entity, target, combat);

    // Multiple targets: attack additional enemies simultaneously (e.g. EWiz hits 2)
    if (combat.getMultipleTargets() > 1) {
      attackAdditionalTargets(entity, target, combat);
    }
  }

  private boolean isInAttackRange(Entity attacker, Entity target, Combat combat) {
    float distance = attacker.getPosition().distanceTo(target.getPosition());
    // Use Collision Radius for range calculation
    float effectiveRange = combat.getRange() + attacker.getCollisionRadius() + target.getCollisionRadius();

    // Minimum range check (e.g. Mortar cannot attack nearby enemies)
    if (combat.getMinimumRange() > 0) {
      float effectiveMinRange = combat.getMinimumRange() + attacker.getCollisionRadius()
          + target.getCollisionRadius();
      if (distance < effectiveMinRange) {
        return false;
      }
    }

    return distance <= effectiveRange;
  }

  private void executeAttack(Entity attacker, Entity target, Combat combat) {
    int baseDamage = combat.getDamageOverride() > 0 ? combat.getDamageOverride() : combat.getDamage();

    // Charge ability: override damage for this attack if charged
    if (attacker instanceof Troop troop) {
      baseDamage = AbilitySystem.getChargeDamage(troop.getAbility(), baseDamage);
    }

    if (combat.isRanged()) {
      Projectile projectile = createAttackProjectile(attacker, target, baseDamage, combat);
      gameState.spawnProjectile(projectile);
    } else {
      // Melee attack, deal damage immediately
      int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, target,
          combat.getCrownTowerDamagePercent());

      if (combat.getAoeRadius() > 0) {
        dealAoeDamage(attacker, target, effectiveDamage, combat.getAoeRadius(),
            combat.getHitEffects());
      } else {
        // Apply effects BEFORE damage to ensure One-Hit Kills still trigger effect logic (e.g. Curse)
        applyEffects(target, combat.getHitEffects());
        dealDamage(target, effectiveDamage);
      }

      // Apply buff-on-damage for melee attacks
      applyBuffOnDamage(combat, target);

      // Reflect: if target has REFLECT ability, deal counter-damage to attacker
      if (target instanceof Troop reflector) {
        int reflectDmg = AbilitySystem.getReflectDamage(reflector);
        if (reflectDmg > 0) {
          applyReflectDamage(reflector, attacker, reflectDmg);
        }
      }
    }

    // Consume charge after attack
    if (attacker instanceof Troop t) {
      AbilitySystem.consumeCharge(t);
    }

    combat.finishAttack();
  }

  /**
   * Finds and attacks additional targets for units with multipleTargets > 1 (e.g. EWiz).
   * The primary target has already been attacked; this method handles the extras.
   */
  private void attackAdditionalTargets(Entity attacker, Entity primaryTarget, Combat combat) {
    int extraTargets = combat.getMultipleTargets() - 1;
    Team enemyTeam = attacker.getTeam().opposite();

    List<Entity> candidates = new ArrayList<>();
    for (Entity e : gameState.getAliveEntities()) {
      if (e == primaryTarget || e.getTeam() != enemyTeam || !e.isTargetable()) {
        continue;
      }
      if (!isInAttackRange(attacker, e, combat)) {
        continue;
      }
      if (!TargetingSystem.canTargetMovementType(combat.getTargetType(), e.getMovementType())) {
        continue;
      }
      candidates.add(e);
    }

    // Sort by distance and pick closest extras
    candidates.sort((a, b) -> {
      float da = attacker.getPosition().distanceTo(a.getPosition());
      float db = attacker.getPosition().distanceTo(b.getPosition());
      return Float.compare(da, db);
    });

    for (int i = 0; i < Math.min(extraTargets, candidates.size()); i++) {
      Entity extraTarget = candidates.get(i);
      int baseDamage = combat.getDamage();

      if (combat.isRanged()) {
        Projectile projectile = createAttackProjectile(attacker, extraTarget, baseDamage, combat);
        gameState.spawnProjectile(projectile);
      } else {
        int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, extraTarget,
            combat.getCrownTowerDamagePercent());
        applyEffects(extraTarget, combat.getHitEffects());
        dealDamage(extraTarget, effectiveDamage);
        applyBuffOnDamage(combat, extraTarget);
      }
    }
  }

  private void applyBuffOnDamage(Combat combat, Entity target) {
    EffectStats buff = combat.getBuffOnDamage();
    if (buff == null || !target.isAlive()) {
      return;
    }
    target.addEffect(new AppliedEffect(buff.getType(), buff.getDuration(), buff.getIntensity()));
  }

  private void applyReflectDamage(Troop reflector, Entity attacker, int reflectDamage) {
    AbilityData data = reflector.getAbility().getData();
    int effectiveDamage = DamageUtil.adjustForCrownTower(reflectDamage, attacker,
        data.getReflectCrownTowerDamagePercent());
    dealDamage(attacker, effectiveDamage);

    // Apply reflect buff (e.g. ZapFreeze stun) to attacker
    if (data.getReflectBuff() != null && data.getReflectBuffDuration() > 0) {
      attacker.addEffect(new AppliedEffect(
          data.getReflectBuff(), data.getReflectBuffDuration(), 0f));
    }
  }

  private void updateProjectiles(float deltaTime) {
    List<Projectile> toRemove = new ArrayList<>();

    for (Projectile projectile : gameState.getProjectiles()) {
      boolean hit = projectile.update(deltaTime);

      if (hit) {
        onProjectileHit(projectile);
      }

      if (!projectile.isActive()) {
        toRemove.add(projectile);
      }
    }

    for (Projectile projectile : toRemove) {
      gameState.removeProjectile(projectile);
    }
  }

  private void onProjectileHit(Projectile projectile) {
    int baseDamage = projectile.getDamage();
    int ctdp = projectile.getCrownTowerDamagePercent();

    if (projectile.isPositionTargeted()) {
      applySpellDamage(projectile.getTeam(), projectile.getTargetX(), projectile.getTargetY(),
          baseDamage, projectile.getAoeRadius(), projectile.getEffects());
    } else if (projectile.hasAoe()) {
      dealAoeDamage(
          projectile.getSource(),
          projectile.getTarget(),
          baseDamage,
          projectile.getAoeRadius(),
          projectile.getEffects());
    } else {
      int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, projectile.getTarget(), ctdp);
      // Apply effects BEFORE damage to ensure One-Hit Kills still trigger effect logic (e.g. Curse)
      applyEffects(projectile.getTarget(), projectile.getEffects());
      dealDamage(projectile.getTarget(), effectiveDamage);
    }

    // Apply projectile-level targetBuff (e.g. Ice Wizard SLOW, EWiz STUN)
    applyTargetBuff(projectile);

    // Chain lightning: spawn sub-projectiles to nearby enemies
    if (projectile.getChainedHitCount() > 0 && projectile.getChainedHitRadius() > 0) {
      processChainLightning(projectile);
    }

    // Spawn sub-projectile on impact (Log rolling, Firecracker explosion)
    if (projectile.getSpawnProjectile() != null) {
      processSpawnProjectile(projectile);
    }
  }

  /**
   * Applies the projectile's targetBuff as a status effect on the hit target.
   */
  private void applyTargetBuff(Projectile projectile) {
    if (projectile.getTargetBuff() == null || projectile.getTarget() == null
        || !projectile.getTarget().isAlive()) {
      return;
    }

    AppliedEffect effect = new AppliedEffect(
        projectile.getTargetBuff(),
        projectile.getBuffDuration(),
        0f, // no intensity modifier for targetBuff
        null
    );
    projectile.getTarget().addEffect(effect);

    // Stun resets attack state
    if (projectile.getTargetBuff() == StatusEffectType.STUN) {
      Combat combat = projectile.getTarget().getCombat();
      if (combat != null) {
        combat.resetAttackState();
      }
    }
  }

  /**
   * Chain lightning: find N closest enemies within chainedHitRadius and create
   * sub-projectiles to each. Excludes the primary target.
   */
  private void processChainLightning(Projectile projectile) {
    Entity primaryTarget = projectile.getTarget();
    if (primaryTarget == null) {
      return;
    }

    float hitX = primaryTarget.getPosition().getX();
    float hitY = primaryTarget.getPosition().getY();
    float chainRadius = projectile.getChainedHitRadius();
    int chainCount = projectile.getChainedHitCount();
    Team team = projectile.getTeam();

    List<Entity> candidates = new ArrayList<>();
    for (Entity e : gameState.getAliveEntities()) {
      if (e == primaryTarget || e.getTeam() == team || !e.isTargetable()) {
        continue;
      }
      float dist = e.getPosition().distanceTo(primaryTarget.getPosition());
      if (dist <= chainRadius + e.getCollisionRadius()) {
        candidates.add(e);
      }
    }

    // Sort by distance, pick closest N
    candidates.sort((a, b) -> {
      float da = a.getPosition().distanceTo(primaryTarget.getPosition());
      float db = b.getPosition().distanceTo(primaryTarget.getPosition());
      return Float.compare(da, db);
    });

    int chainsToSpawn = Math.min(chainCount, candidates.size());
    for (int i = 0; i < chainsToSpawn; i++) {
      Entity chainTarget = candidates.get(i);
      Projectile chain = new Projectile(
          projectile.getSource(), chainTarget,
          projectile.getDamage(), 0, projectile.getProjectileSpeed(),
          projectile.getEffects(),
          projectile.getTargetBuff(), projectile.getBuffDuration(),
          projectile.getCrownTowerDamagePercent());
      gameState.spawnProjectile(chain);
    }
  }

  /**
   * Spawn sub-projectiles on impact (Log rolling projectile, Firecracker explosion).
   */
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

    // Calculate travel direction for non-homing spawned projectiles
    float dx = hitX - projectile.getOriginX();
    float dy = hitY - projectile.getOriginY();
    float dist = Vector2.distance(projectile.getOriginX(), projectile.getOriginY(), hitX, hitY);
    float dirX = dist > 0 ? dx / dist : 0;
    float dirY = dist > 0 ? dy / dist : 1;

    float range = spawnStats.getProjectileRange() > 0 ? spawnStats.getProjectileRange() : 10f;
    float targetX = hitX + dirX * range;
    float targetY = hitY + dirY * range;

    Projectile spawned = new Projectile(
        projectile.getTeam(), hitX, hitY, targetX, targetY,
        spawnStats.getDamage(), spawnStats.getRadius(), spawnStats.getSpeed(),
        spawnStats.getHitEffects());
    gameState.spawnProjectile(spawned);
  }

  /**
   * Creates a projectile for a ranged attack, resolving stats from the combat component's
   * ProjectileStats with fallback to combat-level values.
   */
  private Projectile createAttackProjectile(Entity attacker, Entity target, int damage,
      Combat combat) {
    ProjectileStats stats = combat.getProjectileStats();

    float speed = (stats != null) ? stats.getSpeed() : 0;
    float aoeRadius = (stats != null) ? stats.getRadius() : combat.getAoeRadius();
    List<EffectStats> effects = (stats != null) ? stats.getHitEffects() : combat.getHitEffects();
    StatusEffectType targetBuff = (stats != null) ? stats.getTargetBuff() : null;
    float buffDuration = (stats != null) ? stats.getBuffDuration() : 0f;

    // Use buff-on-damage as targetBuff if no projectile-level buff is set
    if (targetBuff == null && combat.getBuffOnDamage() != null) {
      targetBuff = combat.getBuffOnDamage().getType();
      buffDuration = combat.getBuffOnDamage().getDuration();
    }

    Projectile projectile = new Projectile(
        attacker, target, damage, aoeRadius, speed, effects, targetBuff, buffDuration,
        combat.getCrownTowerDamagePercent());

    // Wire advanced projectile features from stats
    if (stats != null) {
      projectile.setChainedHitRadius(stats.getChainedHitRadius());
      projectile.setChainedHitCount(stats.getChainedHitCount());
      projectile.setSpawnProjectile(stats.getSpawnProjectile());
    }

    return projectile;
  }

  private void dealDamage(Entity target, int damage) {
    if (target == null || !target.isAlive()) {
      return;
    }
    target.getHealth().takeDamage(damage);
  }

  private void applyEffects(Entity target, List<EffectStats> effects) {
    if (target == null || !target.isAlive() || effects == null || effects.isEmpty()) {
      return;
    }

    for (EffectStats stats : effects) {
      // Buildings (MovementType.BUILDING) cannot be Cursed
      if (stats.getType() == StatusEffectType.CURSE
          && target.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      // Pass spawnSpecies (if any) to the AppliedEffect
      AppliedEffect effect = new AppliedEffect(
          stats.getType(),
          stats.getDuration(),
          stats.getIntensity(),
          stats.getSpawnSpecies()
      );
      target.addEffect(effect);

      // Handle Stun Reset Logic (Reset attack windup/charge)
      if (stats.getType() == StatusEffectType.STUN) {
        Combat combat = target.getCombat();
        if (combat != null) {
          combat.resetAttackState();
        }
      }
    }
  }

  private void dealAoeDamage(Entity source, Entity primaryTarget, int damage, float radius,
      List<EffectStats> effects) {
    if (primaryTarget == null) {
      return;
    }
    applySpellDamage(source.getTeam(), primaryTarget.getPosition().getX(),
        primaryTarget.getPosition().getY(), damage, radius, effects);
  }

  /**
   * Apply spell damage to all targetable enemies within radius of the given center point. Accounts
   * for entity size in the radius check.
   */
  public void applySpellDamage(Team sourceTeam, float centerX, float centerY,
      int damage, float radius, List<EffectStats> effects) {
    Team enemyTeam = sourceTeam.opposite();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam) {
        continue;
      }
      if (!entity.isTargetable()) {
        continue;
      }

      float distance = entity.getPosition().distanceTo(centerX, centerY);

      // Use Collision Radius for spell AOE check
      float effectiveRadius = radius + entity.getCollisionRadius();
      if (distance <= effectiveRadius) {
        // Apply effects BEFORE damage
        applyEffects(entity, effects);
        dealDamage(entity, damage);
      }
    }
  }

  /**
   * Check if an entity can attack a target (used for validation).
   */
  public boolean canAttack(Entity attacker, Entity target) {
    if (target == null || !target.isTargetable()) {
      return false;
    }
    if (attacker.getTeam() == target.getTeam()) {
      return false;
    }
    // Inactive towers cannot attack
    if (attacker instanceof Tower tower && (!tower.isActive() || tower.isWakingUp())) {
      return false;
    }

    Combat combat = attacker.getCombat();
    if (combat == null) {
      return false;
    }

    float distance = attacker.getPosition().distanceTo(target.getPosition());
    // Updated to use Collision Radius
    float effectiveRange = combat.getRange() + attacker.getCollisionRadius() + target.getCollisionRadius();

    if (distance > effectiveRange) {
      return false;
    }

    // Minimum range check
    if (combat.getMinimumRange() > 0) {
      float effectiveMinRange = combat.getMinimumRange() + attacker.getCollisionRadius()
          + target.getCollisionRadius();
      if (distance < effectiveMinRange) {
        return false;
      }
    }

    return true;
  }
}
