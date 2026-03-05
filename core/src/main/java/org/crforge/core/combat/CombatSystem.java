package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
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
    int baseDamage = combat.getDamage();

    if (combat.isRanged()) {
      ProjectileStats stats = combat.getProjectileStats();

      float speed = (stats != null) ? stats.getSpeed() : 0;
      float aoeRadius = (stats != null) ? stats.getRadius() : combat.getAoeRadius();
      List<EffectStats> effects = (stats != null) ? stats.getHitEffects() : combat.getHitEffects();
      StatusEffectType targetBuff = (stats != null) ? stats.getTargetBuff() : null;
      float buffDuration = (stats != null) ? stats.getBuffDuration() : 0f;

      Projectile projectile = new Projectile(
          attacker, target, baseDamage, aoeRadius, speed, effects, targetBuff, buffDuration,
          combat.getCrownTowerDamagePercent());
      gameState.spawnProjectile(projectile);
    } else {
      // Melee attack, deal damage immediately
      int effectiveDamage = adjustForCrownTower(baseDamage, target,
          combat.getCrownTowerDamagePercent());

      if (combat.getAoeRadius() > 0) {
        dealAoeDamage(attacker, target, effectiveDamage, combat.getAoeRadius(),
            combat.getHitEffects());
      } else {
        // Apply effects BEFORE damage to ensure One-Hit Kills still trigger effect logic (e.g. Curse)
        applyEffects(target, combat.getHitEffects());
        dealDamage(target, effectiveDamage);
      }
    }

    combat.finishAttack();
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
      int effectiveDamage = adjustForCrownTower(baseDamage, projectile.getTarget(), ctdp);
      // Apply effects BEFORE damage to ensure One-Hit Kills still trigger effect logic (e.g. Curse)
      applyEffects(projectile.getTarget(), projectile.getEffects());
      dealDamage(projectile.getTarget(), effectiveDamage);
    }

    // Apply projectile-level targetBuff (e.g. Ice Wizard SLOW, EWiz STUN)
    applyTargetBuff(projectile);
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
   * Adjusts damage for crown tower damage reduction. Units like Miner deal reduced damage to Towers.
   * Formula: effectiveDamage = baseDamage * (100 + crownTowerDamagePercent) / 100
   * Example: crownTowerDamagePercent = -75 means 25% damage to towers.
   */
  private int adjustForCrownTower(int baseDamage, Entity target, int crownTowerDamagePercent) {
    if (crownTowerDamagePercent == 0 || !(target instanceof Tower)) {
      return baseDamage;
    }
    return Math.max(1, baseDamage * (100 + crownTowerDamagePercent) / 100);
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

    Team enemyTeam = source.getTeam().opposite();
    float centerX = primaryTarget.getPosition().getX();
    float centerY = primaryTarget.getPosition().getY();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam) {
        continue;
      }
      if (!entity.isTargetable()) {
        continue;
      }

      float dx = entity.getPosition().getX() - centerX;
      float dy = entity.getPosition().getY() - centerY;
      float distance = (float) Math.sqrt(dx * dx + dy * dy);

      // Use Collision Radius for AOE check
      float effectiveRadius = radius + entity.getCollisionRadius();
      if (distance <= effectiveRadius) {
        // Apply effects BEFORE damage
        applyEffects(entity, effects);
        dealDamage(entity, damage);
      }
    }
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

      float dx = entity.getPosition().getX() - centerX;
      float dy = entity.getPosition().getY() - centerY;
      float distance = (float) Math.sqrt(dx * dx + dy * dy);

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
