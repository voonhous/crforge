package org.crforge.core.entity.effect;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.combat.DamageUtil;
import org.crforge.core.component.Combat;
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
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Processes all active AreaEffect entities each tick, applying damage and buffs to enemies within
 * the effect radius.
 *
 * <p>One-shot effects (hitSpeed <= 0): apply once on first tick, then expire.
 *
 * <p>Ticking effects (hitSpeed > 0): apply every hitSpeed seconds for the duration.
 */
@RequiredArgsConstructor
public class AreaEffectSystem {

  private final GameState gameState;

  /** Update all active area effects. Should be called once per tick. */
  public void update(float deltaTime) {
    List<AreaEffect> effects = gameState.getEntitiesOfType(AreaEffect.class);
    for (AreaEffect effect : effects) {
      if (!effect.isAlive()) {
        handleControlsBuffCleanup(effect);
        continue;
      }
      applyPull(effect, deltaTime);
      processEffect(effect, deltaTime);
    }
  }

  /**
   * Applies continuous pull toward the effect center for Tornado-like effects. Runs every game tick
   * (not gated by hitSpeed). Pull speed is mass-dependent: lighter units are pulled faster.
   *
   * <p>Stunned/frozen entities skip pull (stagger effect). Buildings cannot be pulled.
   */
  private void applyPull(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();
    BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
    if (buffDef == null || buffDef.getAttractPercentage() <= 0) {
      return;
    }

    float attractPercentage = buffDef.getAttractPercentage();
    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    for (Entity target : gameState.getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      // Buildings cannot be pulled
      if (target.getMovementType() == MovementType.BUILDING) {
        continue;
      }
      if (!canHit(stats, target)) {
        continue;
      }

      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      // Stunned/frozen entities skip pull (stagger effect)
      if (hasStunOrFreeze(target)) {
        continue;
      }

      float distance = (float) Math.sqrt(distanceSq);
      if (distance < 0.01f) {
        continue; // Already at center
      }

      // Pull speed: attractPercentage / (30.0 * mass) tiles/sec
      float mass = target.getMovement() != null ? target.getMovement().getMass() : 6.0f;
      if (mass <= 0) {
        mass = 6.0f; // Fallback to Knight mass
      }
      float pullSpeed = attractPercentage / (30.0f * mass);

      // Direction toward center
      float dx = centerX - target.getPosition().getX();
      float dy = centerY - target.getPosition().getY();
      float ndx = dx / distance;
      float ndy = dy / distance;

      // Displacement this tick, capped to prevent overshooting center
      float displacement = Math.min(pullSpeed * deltaTime, distance);
      target.getPosition().add(ndx * displacement, ndy * displacement);
    }
  }

  /** Returns true if the entity currently has a STUN or FREEZE effect active. */
  private boolean hasStunOrFreeze(Entity target) {
    for (AppliedEffect effect : target.getAppliedEffects()) {
      if (effect.getType() == StatusEffectType.STUN
          || effect.getType() == StatusEffectType.FREEZE) {
        return true;
      }
    }
    return false;
  }

  /**
   * When a controlsBuff area effect dies, remove all applied buffs with the matching buff name from
   * all alive entities. This ensures Tornado buffs are cleaned up when the effect expires.
   */
  private void handleControlsBuffCleanup(AreaEffect effect) {
    if (effect.isBuffsCleaned()) {
      return;
    }
    AreaEffectStats stats = effect.getStats();
    if (!stats.isControlsBuff() || stats.getBuff() == null) {
      return;
    }
    String buffName = stats.getBuff();
    for (Entity entity : gameState.getAliveEntities()) {
      entity
          .getAppliedEffects()
          .removeIf(
              appliedEffect ->
                  appliedEffect.getBuffName() != null
                      && appliedEffect.getBuffName().equals(buffName));
    }
    effect.setBuffsCleaned(true);
  }

  private void processEffect(AreaEffect effect, float deltaTime) {
    if (effect.isOneShot()) {
      // One-shot: apply once on the first tick, then let update() expire it
      if (!effect.isInitialApplied()) {
        applyToTargets(effect);
        effect.setInitialApplied(true);
      }
    } else {
      // Ticking: accumulate time and apply when threshold reached
      float acc = effect.getTickAccumulator() + deltaTime;

      while (acc >= effect.getStats().getHitSpeed()) {
        acc -= effect.getStats().getHitSpeed();
        applyToTargets(effect);
      }

      effect.setTickAccumulator(acc);
    }
  }

  private void applyToTargets(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();

    // Clone spell: duplicate friendly troops in the area
    if (stats.isClone()) {
      applyClone(effect);
      return;
    }

    // Check if this area effect heals friendlies instead of damaging enemies
    BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
    if (buffDef != null && buffDef.getHealPerSecond() > 0) {
      applyHealToFriendlies(effect, buffDef);
      return;
    }

    // Check if this is a friendly buff effect (e.g. Rage) -- no damage, positive speed buff
    if (buffDef != null && isFriendlyBuff(buffDef) && effect.getEffectiveDamage() == 0) {
      applyBuffToFriendlies(effect);
      return;
    }

    // Lightning-style: each tick hits the single highest-HP target not yet struck
    if (stats.isHitBiggestTargets()) {
      applyToBiggestTarget(effect);
      return;
    }

    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();
    int damage = effect.getEffectiveDamage();
    int ctdp = effect.getEffectiveCrownTowerDamagePercent();

    // Determine if this effect can bypass hidden buildings (Earthquake, Freeze)
    boolean bypassesHidden = stats.isAffectsHidden();
    if (!bypassesHidden) {
      // Fallback for legacy data that may not have the affectsHidden field set
      StatusEffectType buffType =
          stats.getBuff() != null ? StatusEffectType.fromBuffName(stats.getBuff()) : null;
      bypassesHidden =
          buffType == StatusEffectType.EARTHQUAKE || buffType == StatusEffectType.FREEZE;
    }

    List<Entity> aliveEntities = gameState.getAliveEntities();
    for (Entity target : aliveEntities) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      // Hidden buildings are skipped by most effects, but Earthquake and Freeze bypass
      if (!target.isTargetable()) {
        if (bypassesHidden && target instanceof Building building && building.isHidden()) {
          // Allow this effect to hit the hidden building
        } else {
          continue;
        }
      }
      if (!canHit(stats, target)) {
        continue;
      }

      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();

      if (distanceSq <= effectiveRadius * effectiveRadius) {
        // Apply buff first (before damage, consistent with CombatSystem convention)
        applyBuff(effect, target);

        // Apply damage with building bonus and crown tower modifiers
        if (damage > 0) {
          int effectiveDamage = damage;

          // Apply building damage percent bonus first (e.g. Earthquake deals 4.5x to buildings)
          // Towers are buildings in Clash Royale, so BUILDING and TOWER both qualify
          if (effect.getBuildingDamagePercent() > 0
              && (target.getEntityType() == EntityType.BUILDING
                  || target.getEntityType() == EntityType.TOWER)) {
            effectiveDamage = effectiveDamage * (100 + effect.getBuildingDamagePercent()) / 100;
          }

          // Then apply crown tower damage reduction
          effectiveDamage = DamageUtil.adjustForCrownTower(effectiveDamage, target, ctdp);

          target.getHealth().takeDamage(effectiveDamage);
        }
      }
    }
  }

  /**
   * Clones all eligible friendly troops within the area effect radius. Each clone is an exact copy
   * of the original but with 1 HP. Buildings, towers, and existing clones are excluded.
   *
   * <p>The original troop is displaced slightly forward (toward the enemy side) to make room for
   * the clone, which spawns at the original's pre-displacement position.
   */
  private void applyClone(AreaEffect effect) {
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
      if (!canHit(stats, entity)) {
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

  /**
   * Applies damage and buff to the single highest-HP enemy in range that has not been hit yet. Used
   * by Lightning to strike up to 3 different targets across its ticking lifetime.
   */
  private void applyToBiggestTarget(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();
    int damage = effect.getEffectiveDamage();
    int ctdp = effect.getEffectiveCrownTowerDamagePercent();

    Entity bestTarget = null;
    int bestEffectiveHp = -1;

    for (Entity target : gameState.getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      if (!canHit(stats, target)) {
        continue;
      }
      if (effect.getHitEntityIds().contains(target.getId())) {
        continue;
      }

      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      // Effective HP = current HP + shield (higher total gets struck first)
      int effectiveHp = target.getHealth().getCurrent() + target.getHealth().getShield();
      if (effectiveHp > bestEffectiveHp) {
        bestEffectiveHp = effectiveHp;
        bestTarget = target;
      }
    }

    if (bestTarget == null) {
      return;
    }

    effect.getHitEntityIds().add(bestTarget.getId());
    applyBuff(effect, bestTarget);

    if (damage > 0) {
      int effectiveDamage = damage;

      // Apply building damage percent bonus first
      if (effect.getBuildingDamagePercent() > 0
          && (bestTarget.getEntityType() == EntityType.BUILDING
              || bestTarget.getEntityType() == EntityType.TOWER)) {
        effectiveDamage = effectiveDamage * (100 + effect.getBuildingDamagePercent()) / 100;
      }

      // Then apply crown tower damage reduction
      effectiveDamage = DamageUtil.adjustForCrownTower(effectiveDamage, bestTarget, ctdp);

      bestTarget.getHealth().takeDamage(effectiveDamage);
    }
  }

  /**
   * Heals friendly units within the area effect radius. Used when the buff defines healPerSecond
   * (e.g. HealSpiritBuff). Heal amount is healPerSecond * buffDuration for one-shot effects, or
   * healPerSecond * hitSpeed for ticking effects.
   */
  private void applyHealToFriendlies(AreaEffect effect, BuffDefinition buffDef) {
    AreaEffectStats stats = effect.getStats();
    float duration = stats.getBuffDuration() > 0 ? stats.getBuffDuration() : 1.0f;
    int healAmount = Math.round(buffDef.getHealPerSecond() * duration);

    Team friendlyTeam = effect.getTeam();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    for (Entity target : gameState.getAliveEntities()) {
      if (target.getTeam() != friendlyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      if (!canHit(stats, target)) {
        continue;
      }
      // Heal only targets troops, not buildings/towers
      if (target.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq <= effectiveRadius * effectiveRadius) {
        target.getHealth().heal(healAmount);
      }
    }
  }

  /**
   * Returns true if the buff is a positive friendly buff (like Rage) rather than a debuff. Detected
   * by having positive speed multipliers and no damage component.
   */
  private boolean isFriendlyBuff(BuffDefinition buffDef) {
    return buffDef.getSpeedMultiplier() > 0 && buffDef.getDamagePerSecond() == 0;
  }

  /**
   * Applies a buff to friendly units within the area effect radius. Used for positive buffs like
   * Rage that should affect same-team entities.
   */
  private void applyBuffToFriendlies(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team friendlyTeam = effect.getTeam();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    for (Entity target : gameState.getAliveEntities()) {
      if (target.getTeam() != friendlyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      if (!canHit(stats, target)) {
        continue;
      }

      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq <= effectiveRadius * effectiveRadius) {
        applyBuff(effect, target);
      }
    }
  }

  private boolean canHit(AreaEffectStats stats, Entity target) {
    MovementType mt = target.getMovementType();
    if (mt == MovementType.AIR) {
      return stats.isHitsAir();
    }
    // GROUND and BUILDING
    return stats.isHitsGround();
  }

  private void applyBuff(AreaEffect effect, Entity target) {
    AreaEffectStats stats = effect.getStats();
    if (stats.getBuff() == null) {
      return;
    }
    StatusEffectType effectType = StatusEffectType.fromBuffName(stats.getBuff());
    if (effectType == null) {
      return;
    }

    // Pass buff name for data-driven multiplier resolution in StatusEffectSystem
    float duration = stats.getBuffDuration() > 0 ? stats.getBuffDuration() : 1.0f;
    if (stats.isCapBuffTimeToAreaEffectTime()) {
      duration = Math.min(duration, effect.getRemainingLifetime());
    }
    target.addEffect(new AppliedEffect(effectType, duration, stats.getBuff()));

    // Handle Stun/Freeze Reset Logic (Reset attack windup and charge ability)
    if (effectType == StatusEffectType.STUN || effectType == StatusEffectType.FREEZE) {
      Combat combat = target.getCombat();
      if (combat != null) {
        combat.resetAttackState();
      }
      // Reset charge ability state (Prince, Dark Prince, Battle Ram, Ram Rider)
      // Reset variable damage state (Inferno Dragon, Inferno Tower)
      if (target instanceof Troop troop) {
        AbilitySystem.consumeCharge(troop);
        AbilitySystem.resetVariableDamage(troop);
      } else if (target instanceof Building building && building.getAbility() != null) {
        AbilitySystem.resetVariableDamage(building.getAbility(), building.getCombat());
      }
    }

    // Freeze forces hidden buildings (Tesla) to reveal
    if (effectType == StatusEffectType.FREEZE && target instanceof Building building) {
      AbilitySystem.forceRevealHiding(building);
    }
  }
}
