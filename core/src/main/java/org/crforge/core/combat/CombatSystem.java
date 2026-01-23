package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.Projectile;
import org.crforge.core.entity.Tower;
import org.crforge.core.entity.Troop;
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
    // Process troop attacks
    for (Entity entity : gameState.getAliveEntities()) {
      if (entity instanceof Troop troop) {
        processTroopCombat(troop);
      } else if (entity instanceof Tower tower) {
        processTowerCombat(tower);
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

  private void processTowerCombat(Tower tower) {
    if (!tower.hasTarget()) {
      return;
    }

    Combat combat = tower.getCombat();

    // Check if in range
    float distance = tower.getPosition().distanceTo(tower.getCurrentTarget().getPosition());
    float effectiveRange =
        combat.getRange() + (tower.getSize() + tower.getCurrentTarget().getSize()) / 2f;

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
    executeAttack(tower, tower.getCurrentTarget(), combat);
  }

  private void executeAttack(Entity attacker, Entity target, Combat combat) {
    if (combat.isRanged()) {
      // Spawn projectile
      Projectile projectile =
          new Projectile(attacker, target, combat.getDamage(), combat.getAoeRadius());
      gameState.spawnProjectile(projectile);
    } else {
      // Melee attack - deal damage immediately
      if (combat.getAoeRadius() > 0) {
        dealAoeDamage(attacker, target, combat.getDamage(), combat.getAoeRadius());
      } else {
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
    if (projectile.hasAoe()) {
      dealAoeDamage(
          projectile.getSource(),
          projectile.getTarget(),
          projectile.getDamage(),
          projectile.getAoeRadius());
    } else {
      dealDamage(projectile.getTarget(), projectile.getDamage());
    }
  }

  private void dealDamage(Entity target, int damage) {
    if (target == null || !target.isAlive()) {
      return;
    }
    target.getHealth().takeDamage(damage);
  }

  private void dealAoeDamage(Entity source, Entity primaryTarget, int damage, float radius) {
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

    Combat combat;
    if (attacker instanceof Troop troop) {
      combat = troop.getCombat();
    } else if (attacker instanceof Tower tower) {
      combat = tower.getCombat();
    } else {
      return false;
    }

    float distance = attacker.getPosition().distanceTo(target.getPosition());
    float effectiveRange = combat.getRange() + (attacker.getSize() + target.getSize()) / 2f;

    return distance <= effectiveRange;
  }
}
