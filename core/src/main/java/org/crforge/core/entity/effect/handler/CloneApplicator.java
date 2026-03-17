package org.crforge.core.entity.effect.handler;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Clones all eligible friendly troops within the area effect radius. Each clone is an exact copy of
 * the original but with 1 HP. Buildings, towers, and existing clones are excluded.
 *
 * <p>The original troop is displaced slightly forward (toward the enemy side) to make room for the
 * clone, which spawns at the original's pre-displacement position.
 */
public class CloneApplicator {

  private final AreaEffectContext ctx;
  private final GameState gameState;

  CloneApplicator(AreaEffectContext ctx, GameState gameState) {
    this.ctx = ctx;
    this.gameState = gameState;
  }

  public void applyClone(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team friendlyTeam = effect.getTeam();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    // Collect eligible troops first to avoid concurrent modification
    List<Troop> eligibleTroops = new ArrayList<>();
    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != friendlyTeam) {
        continue;
      }
      if (!(entity instanceof Troop troop)) {
        continue;
      }
      // Exclude clones (cannot be re-cloned)
      if (troop.isClone()) {
        continue;
      }
      // Exclude deploying troops
      if (troop.isDeploying()) {
        continue;
      }
      // Exclude attached troops (e.g. riders)
      if (troop.isAttached()) {
        continue;
      }
      if (!ctx.canHit(stats, entity)) {
        continue;
      }

      float distanceSq = entity.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + entity.getCollisionRadius();
      if (distanceSq <= effectiveRadius * effectiveRadius) {
        eligibleTroops.add(troop);
      }
    }

    // Clone each eligible troop
    for (Troop original : eligibleTroops) {
      float origX = original.getPosition().getX();
      float origY = original.getPosition().getY();

      // Displace original slightly forward (toward enemy side)
      float displaceDy = (friendlyTeam == Team.BLUE) ? 0.5f : -0.5f;
      original.getPosition().add(0, displaceDy);

      // Create clone at original's pre-displacement position
      Troop clone = cloneTroop(original, origX, origY);
      gameState.spawnEntity(clone);
    }
  }

  /**
   * Creates a clone of the given troop at the specified position. The clone has 1 HP (max 1),
   * shield capped to 1, no inherited buffs, and instant deploy. Combat stats (damage, range, etc.)
   * are preserved from the original. The clone flag is set to prevent re-cloning.
   */
  private Troop cloneTroop(Troop original, float x, float y) {
    // Clone gets 1 HP, shield = 1 if original type has shield
    int cloneShield = original.getHealth().getShieldMax() > 0 ? 1 : 0;

    // Rebuild Combat from original's combat component (fresh state, same config)
    Combat originalCombat = original.getCombat();
    Combat cloneCombat = null;
    if (originalCombat != null) {
      float initialLoad = originalCombat.getLoadTime();
      cloneCombat =
          Combat.builder()
              .damage(originalCombat.getDamage())
              .range(originalCombat.getRange())
              .sightRange(originalCombat.getSightRange())
              .attackCooldown(originalCombat.getAttackCooldown())
              .loadTime(originalCombat.getLoadTime())
              .accumulatedLoadTime(initialLoad)
              .aoeRadius(originalCombat.getAoeRadius())
              .targetType(originalCombat.getTargetType())
              .hitEffects(originalCombat.getHitEffects())
              .projectileStats(originalCombat.getProjectileStats())
              .multipleTargets(originalCombat.getMultipleTargets())
              .multipleProjectiles(originalCombat.getMultipleProjectiles())
              .selfAsAoeCenter(originalCombat.isSelfAsAoeCenter())
              .buffOnDamage(originalCombat.getBuffOnDamage())
              .areaEffectOnHit(originalCombat.getAreaEffectOnHit())
              .attackDashTime(originalCombat.getAttackDashTime())
              .attackPushBack(originalCombat.getAttackPushBack())
              .kamikaze(originalCombat.isKamikaze())
              .targetOnlyBuildings(originalCombat.isTargetOnlyBuildings())
              .targetOnlyTroops(originalCombat.isTargetOnlyTroops())
              .minimumRange(originalCombat.getMinimumRange())
              .crownTowerDamagePercent(originalCombat.getCrownTowerDamagePercent())
              .ignoreTargetsWithBuff(originalCombat.getIgnoreTargetsWithBuff())
              .build();
    }

    // Copy movement with same flags
    Movement originalMovement = original.getMovement();
    Movement cloneMovement =
        new Movement(
            originalMovement.getSpeed(),
            originalMovement.getMass(),
            originalMovement.getCollisionRadius(),
            originalMovement.getVisualRadius(),
            originalMovement.getType());
    cloneMovement.setIgnorePushback(originalMovement.isIgnorePushback());
    cloneMovement.setJumpEnabled(originalMovement.isJumpEnabled());
    cloneMovement.setHovering(originalMovement.isHovering());

    // Fresh ability component from original's ability data
    AbilityComponent cloneAbility = null;
    if (original.getAbility() != null) {
      cloneAbility = new AbilityComponent(original.getAbility().getData());
    }

    // Deep copy spawner component if present (death spawns, live spawns)
    SpawnerComponent cloneSpawner = null;
    if (original.getSpawner() != null) {
      SpawnerComponent origSpawner = original.getSpawner();
      SpawnerComponent.SpawnerComponentBuilder spawnerBuilder =
          SpawnerComponent.builder()
              .deathDamage(origSpawner.getDeathDamage())
              .deathDamageRadius(origSpawner.getDeathDamageRadius())
              .deathPushback(origSpawner.getDeathPushback())
              .deathSpawns(origSpawner.getDeathSpawns())
              .deathAreaEffect(origSpawner.getDeathAreaEffect())
              .deathSpawnProjectile(origSpawner.getDeathSpawnProjectile())
              .manaOnDeathForOpponent(origSpawner.getManaOnDeathForOpponent())
              .rarity(origSpawner.getRarity())
              .level(origSpawner.getLevel())
              .selfDestruct(origSpawner.isSelfDestruct());

      // Copy live spawn config if present
      if (origSpawner.hasLiveSpawn()) {
        spawnerBuilder
            .spawnInterval(origSpawner.getSpawnInterval())
            .spawnPauseTime(origSpawner.getSpawnPauseTime())
            .unitsPerWave(origSpawner.getUnitsPerWave())
            .spawnStartTime(origSpawner.getSpawnStartTime())
            .currentTimer(origSpawner.getSpawnStartTime())
            .spawnStats(origSpawner.getSpawnStats())
            .formationRadius(origSpawner.getFormationRadius())
            .spawnLimit(origSpawner.getSpawnLimit())
            .destroyAtLimit(origSpawner.isDestroyAtLimit());
      }

      cloneSpawner = spawnerBuilder.build();
    }

    return Troop.builder()
        .name(original.getName())
        .team(original.getTeam())
        .position(new Position(x, y))
        .health(new Health(1, cloneShield))
        .movement(cloneMovement)
        .combat(cloneCombat)
        .deployTime(0f)
        .deployTimer(0f)
        .spawner(cloneSpawner)
        .ability(cloneAbility)
        .level(original.getLevel())
        .clone(true)
        .build();
  }
}
