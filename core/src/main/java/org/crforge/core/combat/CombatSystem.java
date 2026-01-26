package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
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
    // Process attacks
    for (Entity entity : gameState.getAliveEntities()) {
      if (entity instanceof Troop troop) {
        processTroopCombat(troop);
      } else if (entity instanceof Building building) {
        // Handles both Tower and regular Buildings (like Cannon)
        processBuildingCombat(building);
      }
    }

    // Update and process projectiles
    updateProjectiles(deltaTime);
  }

  private void processTroopCombat(Troop troop) {
    if (troop.isDeploying()) {
      return;
    }
    if (!troop.hasTarget()) {
      return;
    }

    Combat combat = troop.getCombat();

    // Check if in attack range
    if (!troop.isInAttackRange()) {
      return;
    }

    // Check if can attack (cooldown ready)
    if (!combat.canAttack()) {
      return;
    }

    // Start attack if not already loading
    if (!combat.isLoading()) {
      combat.startAttack();
    }

    // Check if load time complete
    if (combat.isLoading()) {
      return;
    }

    // Execute attack
    executeAttack(troop, troop.getCurrentTarget(), combat);
  }

  private void processBuildingCombat(Building building) {
    if (!building.hasTarget()) {
      return;
    }

    Combat combat = building.getCombat();

    // Buildings like Tesla might exist without combat component
    if (combat == null) {
      return;
    }

    // Check if in range
    float distance = building.getPosition().distanceTo(building.getCurrentTarget().getPosition());
    float effectiveRange =
        combat.getRange() + (building.getSize() + building.getCurrentTarget().getSize()) / 2f;

    if (distance > effectiveRange) {
      return;
    }

    // Check cooldown
    if (!combat.canAttack()) {
      return;
    }

    // Start attack
    if (!combat.isLoading()) {
      combat.startAttack();
    }

    if (combat.isLoading()) {
      return;
    }

    // Execute attack
    executeAttack(building, building.getCurrentTarget(), combat);
  }

  private void executeAttack(Entity attacker, Entity target, Combat combat) {
    if (combat.isRanged()) {
      // Spawn projectile
      Projectile projectile =
          new Projectile(attacker, target, combat.getDamage(), combat.getAoeRadius(), combat.getHitEffects());
      gameState.spawnProjectile(projectile);
    } else {
      // Melee attack - deal damage immediately
      if (combat.getAoeRadius() > 0) {
        dealAoeDamage(attacker, target, combat.getDamage(), combat.getAoeRadius(), combat.getHitEffects());
      } else {
        dealDamage(target, combat.getDamage());
        applyEffects(target, combat.getHitEffects());
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
    if (projectile.hasAoe()) {
      dealAoeDamage(
          projectile.getSource(),
          projectile.getTarget(),
          projectile.getDamage(),
          projectile.getAoeRadius(),
          projectile.getEffects());
    } else {
      dealDamage(projectile.getTarget(), projectile.getDamage());
      applyEffects(projectile.getTarget(), projectile.getEffects());
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
      AppliedEffect effect = new AppliedEffect(stats.getType(), stats.getDuration(), stats.getIntensity());
      target.addEffect(effect);
    }
  }

  private void dealAoeDamage(Entity source, Entity primaryTarget, int damage, float radius, List<EffectStats> effects) {
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

      // Check if within AOE radius (accounting for entity size)
      float effectiveRadius = radius + entity.getSize() / 2f;
      if (distance <= effectiveRadius) {
        dealDamage(entity, damage);
        applyEffects(entity, effects);
      }
    }
  }

  // Overload for backward compatibility in internal calls if needed (though not used above)
  private void dealAoeDamage(Entity source, Entity primaryTarget, int damage, float radius) {
    dealAoeDamage(source, primaryTarget, damage, radius, null);
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

    Combat combat = attacker.getCombat();
    if (combat == null) {
      return false;
    }

    float distance = attacker.getPosition().distanceTo(target.getPosition());
    float effectiveRange = combat.getRange() + (attacker.getSize() + target.getSize()) / 2f;

    return distance <= effectiveRange;
  }
}
