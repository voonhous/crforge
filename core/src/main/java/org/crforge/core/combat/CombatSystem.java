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
      return;
    }

    Entity target = combat.getCurrentTarget();

    // Check if in attack range
    if (!isInAttackRange(entity, target, combat)) {
      // If out of range, cancel any ongoing attack
      if (combat.isAttacking()) {
        combat.setAttacking(false);
        combat.setCurrentLoadTime(0);
      }
      return;
    }

    // Check if can attack (cooldown ready)
    if (!combat.canAttack()) {
      return;
    }

    // Start attack if not already loading/attacking
    if (!combat.isAttacking()) {
      // Determine windup time
      float windup = combat.isFirstAttackOnTarget()
          ? combat.getFirstAttackCooldown()
          : combat.getLoadTime();

      combat.startAttack(windup);
    }

    // Check if load time complete
    if (combat.isLoading()) {
      return;
    }

    // Execute attack
    executeAttack(entity, target, combat);
  }

  private boolean isInAttackRange(Entity attacker, Entity target, Combat combat) {
    float distance = attacker.getPosition().distanceTo(target.getPosition());
    // Updated to use Collision Radius
    float effectiveRange = combat.getRange() + attacker.getCollisionRadius() + target.getCollisionRadius();
    return distance <= effectiveRange;
  }

  private void executeAttack(Entity attacker, Entity target, Combat combat) {
    if (combat.isRanged()) {
      ProjectileStats stats = combat.getProjectileStats();

      float speed = (stats != null) ? stats.getSpeed() : 0;
      float aoeRadius = (stats != null) ? stats.getRadius() : combat.getAoeRadius();
      List<EffectStats> effects = (stats != null) ? stats.getHitEffects() : combat.getHitEffects();

      Projectile projectile =
          new Projectile(attacker, target, combat.getDamage(), aoeRadius, speed, effects);
      gameState.spawnProjectile(projectile);
    } else {
      // Melee attack, deal damage immediately
      if (combat.getAoeRadius() > 0) {
        dealAoeDamage(attacker, target, combat.getDamage(), combat.getAoeRadius(),
            combat.getHitEffects());
      } else {
        // Apply effects BEFORE damage to ensure One-Hit Kills still trigger effect logic (e.g. Curse)
        applyEffects(target, combat.getHitEffects());
        dealDamage(target, combat.getDamage());
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
    if (projectile.isPositionTargeted()) {
      applySpellDamage(projectile.getTeam(), projectile.getTargetX(), projectile.getTargetY(),
          projectile.getDamage(), projectile.getAoeRadius(), projectile.getEffects());
    } else if (projectile.hasAoe()) {
      dealAoeDamage(
          projectile.getSource(),
          projectile.getTarget(),
          projectile.getDamage(),
          projectile.getAoeRadius(),
          projectile.getEffects());
    } else {
      // Apply effects BEFORE damage to ensure One-Hit Kills still trigger effect logic (e.g. Curse)
      applyEffects(projectile.getTarget(), projectile.getEffects());
      dealDamage(projectile.getTarget(), projectile.getDamage());
    }
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

    return distance <= effectiveRange;
  }
}
