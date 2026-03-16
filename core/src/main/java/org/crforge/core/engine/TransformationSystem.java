package org.crforge.core.engine;

import java.util.List;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TransformationConfig;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/**
 * Handles HP-threshold transformations. When a troop's HP drops to or below its configured
 * threshold percentage, it is replaced by a new entity with different stats (e.g. GoblinDemolisher
 * ranged form -> kamikaze form at 50% HP).
 *
 * <p>The old entity is marked dead (suppressing death handlers) and removed. The new entity spawns
 * at the same position with HP carried over proportionally.
 */
class TransformationSystem {

  private final GameState gameState;

  TransformationSystem(GameState gameState) {
    this.gameState = gameState;
  }

  /** Check all alive troops for HP-threshold transformations. */
  void update() {
    // Snapshot the alive list since we may modify entities during iteration
    List<Entity> snapshot = List.copyOf(gameState.getAliveEntities());

    for (Entity entity : snapshot) {
      if (!(entity instanceof Troop troop)) {
        continue;
      }
      TransformationConfig config = troop.getTransformConfig();
      if (config == null || troop.isTransformed()) {
        continue;
      }
      // Skip deploying troops
      if (troop.isDeploying()) {
        continue;
      }
      // Only transform if alive (lethal damage should kill normally, not transform)
      if (!troop.getHealth().isAlive()) {
        continue;
      }
      // Check HP threshold: transform when HP percentage <= configured threshold
      float hpPercent = troop.getHealth().percentage() * 100f;
      if (hpPercent <= config.healthPercent()) {
        transform(troop, config);
      }
    }
  }

  /**
   * Replaces the old troop with a new entity built from the transformation target stats. Carries
   * over position, team, current HP (proportionally), and level. The old entity is marked dead to
   * suppress death handlers.
   */
  private void transform(Troop old, TransformationConfig config) {
    TroopStats kamikazeStats = config.transformStats();

    // Capture state from old entity
    float x = old.getPosition().getX();
    float y = old.getPosition().getY();
    int currentHp = old.getHealth().getCurrent();
    int level = old.getLevel();

    // Determine rarity from spawner (always available since GoblinDemolisher has
    // deathSpawnProjectile)
    Rarity rarity = Rarity.RARE;
    if (old.getSpawner() != null) {
      rarity = old.getSpawner().getRarity();
    }

    // Mark old entity dead to suppress death handler (markDead sets dead=true,
    // so processDeaths sees isDead()=true and skips the death handler)
    old.markDead();
    gameState.removeEntity(old);

    // Scale kamikaze stats by level
    int scaledMaxHp = LevelScaling.scaleCard(kamikazeStats.getHealth(), rarity, level);
    int scaledDamage = LevelScaling.scaleCard(kamikazeStats.getDamage(), rarity, level);

    // Build combat component (includes kamikaze=true, targetOnlyBuildings=true from stats)
    float initialLoad = kamikazeStats.isNoPreload() ? 0f : kamikazeStats.getLoadTime();
    Combat combat =
        EntityFactory.buildCombatComponent(kamikazeStats, scaledDamage, initialLoad)
            .aoeRadius(kamikazeStats.getAoeRadius())
            .multipleTargets(kamikazeStats.getMultipleTargets())
            .multipleProjectiles(kamikazeStats.getMultipleProjectiles())
            .buffOnDamage(kamikazeStats.getBuffOnDamage())
            .build();

    // Build health: start at max, then damage down to carry over current HP
    // Cap carried HP to scaled max (in case old form had more HP than kamikaze form)
    int effectiveHp = Math.min(currentHp, scaledMaxHp);
    Health health = new Health(scaledMaxHp);
    if (effectiveHp < scaledMaxHp) {
      health.takeDamage(scaledMaxHp - effectiveHp);
    }

    // Build SpawnerComponent for death mechanics (deathSpawnProjectile)
    SpawnerComponent spawner = null;
    if (kamikazeStats.getDeathSpawnProjectile() != null) {
      ProjectileStats deathProjStats = kamikazeStats.getDeathSpawnProjectile();
      int scaledProjDamage = LevelScaling.scaleCard(deathProjStats.getDamage(), rarity, level);
      ProjectileStats scaledProj = deathProjStats.withDamage(scaledProjDamage);
      if (deathProjStats.getSpawnCharacter() != null) {
        scaledProj = scaledProj.withSpawnCharacter(deathProjStats.getSpawnCharacter());
      }

      spawner =
          SpawnerComponent.builder()
              .deathSpawnProjectile(scaledProj)
              .rarity(rarity)
              .level(level)
              .build();
    }

    Movement movement =
        new Movement(
            kamikazeStats.getSpeed(),
            kamikazeStats.getMass(),
            kamikazeStats.getCollisionRadius(),
            kamikazeStats.getVisualRadius(),
            kamikazeStats.getMovementType());
    movement.setIgnorePushback(kamikazeStats.isIgnorePushback());

    Troop kamikaze =
        Troop.builder()
            .name(kamikazeStats.getName())
            .team(old.getTeam())
            .position(new Position(x, y))
            .health(health)
            .movement(movement)
            .combat(combat)
            .spawner(spawner)
            .deployTime(0f)
            .deployTimer(0f)
            .transformed(true)
            .lifeTimer(kamikazeStats.getLifeTime())
            .level(level)
            .build();

    gameState.spawnEntity(kamikaze);
  }
}
