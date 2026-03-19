package org.crforge.core.combat;

import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.projectile.Projectile;

/**
 * Orchestrates the projectile lifecycle: movement, hit detection, and cleanup. Delegates creation
 * to {@link ProjectileFactory}, hit processing to {@link ProjectileHitProcessor}, piercing
 * detection to {@link PiercingHitDetector}, and knockback to {@link KnockbackHelper}.
 */
public class ProjectileSystem {

  private final GameState gameState;
  private final ProjectileFactory projectileFactory;
  private final ProjectileHitProcessor hitProcessor;
  private final PiercingHitDetector piercingHitDetector;

  public ProjectileSystem(
      GameState gameState, AoeDamageService aoeDamageService, CombatAbilityBridge abilityBridge) {
    this.gameState = gameState;
    KnockbackHelper knockbackHelper = new KnockbackHelper(gameState);
    this.projectileFactory = new ProjectileFactory(gameState);
    this.hitProcessor =
        new ProjectileHitProcessor(gameState, aoeDamageService, knockbackHelper, abilityBridge);
    this.piercingHitDetector =
        new PiercingHitDetector(gameState, aoeDamageService, knockbackHelper);
  }

  /** Backward-compatible constructor that creates a DefaultCombatAbilityBridge internally. */
  public ProjectileSystem(GameState gameState, AoeDamageService aoeDamageService) {
    this(gameState, aoeDamageService, new DefaultCombatAbilityBridge());
  }

  /** Sets the UnitSpawner callback for spawn-on-impact projectiles (e.g. PhoenixFireball). */
  public void setUnitSpawner(UnitSpawner unitSpawner) {
    hitProcessor.setUnitSpawner(unitSpawner);
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
      boolean wasActive = projectile.isActive();
      boolean hit = projectile.update(deltaTime);

      if (hit) {
        hitProcessor.onProjectileHit(projectile);
      }

      // Piercing projectiles check for hits every tick while still active
      if (projectile.isPiercing() && projectile.isActive()) {
        piercingHitDetector.processPiercingHits(projectile);
      }

      // Piercing projectile expired: spawn character at current position (e.g. BarbLog Barbarian)
      if (wasActive
          && !projectile.isActive()
          && projectile.isPiercing()
          && projectile.getSpawnCharacterStats() != null
          && hitProcessor.getUnitSpawner() != null) {
        hitProcessor.spawnCharacterOnImpact(projectile);
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
    return projectileFactory.createAttackProjectile(attacker, target, damage, combat);
  }

  /** Fires scatter projectiles in a fan pattern (Hunter shotgun). */
  void fireScatterProjectiles(Entity attacker, Entity target, int damage, Combat combat) {
    projectileFactory.fireScatterProjectiles(attacker, target, damage, combat);
  }
}
