package org.crforge.core.entity.effect;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.handler.ChargeHandler;
import org.crforge.core.ability.handler.HidingHandler;
import org.crforge.core.ability.handler.VariableDamageHandler;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.ScaledDamageTier;
import org.crforge.core.card.SpawnSequenceEntry;
import org.crforge.core.card.TroopStats;
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
import org.crforge.core.entity.structure.Tower;
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

  /** Callback for spawning units (wired to SpawnerSystem::spawnUnit). */
  @FunctionalInterface
  public interface UnitSpawner {
    void spawnUnit(
        float x, float y, Team team, TroopStats stats, Rarity rarity, int level, float deployTime);
  }

  private final GameState gameState;

  @Setter private UnitSpawner unitSpawner;

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
      processSpawn(effect, deltaTime);
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
    if (!stats.isControlsBuff() || stats.getBuffApplications().isEmpty()) {
      return;
    }
    for (BuffApplication buffApp : stats.getBuffApplications()) {
      String buffName = buffApp.buffName();
      for (Entity entity : gameState.getAliveEntities()) {
        entity.getAppliedEffects().removeIf(e -> buffName.equals(e.getBuffName()));
      }
    }
    effect.setBuffsCleaned(true);
  }

  private void processEffect(AreaEffect effect, float deltaTime) {
    // Laser ball effect (DarkMagic): scan-based tiers with 100ms damage ticks
    if (!effect.getStats().getDamageTiers().isEmpty()) {
      processLaserBall(effect, deltaTime);
      return;
    }

    // Targeted effect (Vines): select specific targets with staggered delays, then DOT
    if (effect.getStats().getTargetCount() > 0) {
      processTargetedEffect(effect, deltaTime);
      return;
    }

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

  /**
   * Handles delayed character spawning for area effects (e.g. Royal Delivery spawns a
   * DeliveryRecruit at 2.05s). Increments the spawn delay accumulator and fires the spawn once the
   * threshold is reached. For spawn sequences (Graveyard), delegates to processSpawnSequence.
   */
  private void processSpawn(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();
    if (unitSpawner == null) {
      return;
    }

    // Spawn sequence (e.g. Graveyard: 13 skeletons with staggered timing)
    if (!stats.getSpawnSequence().isEmpty()) {
      processSpawnSequence(effect, deltaTime);
      return;
    }

    // Single delayed spawn (e.g. Royal Delivery -> DeliveryRecruit)
    if (stats.getSpawnCharacter() == null || effect.isSpawnTriggered()) {
      return;
    }

    effect.setSpawnDelayAccumulator(effect.getSpawnDelayAccumulator() + deltaTime);
    if (effect.getSpawnDelayAccumulator() >= stats.getSpawnInitialDelay()) {
      float x = effect.getPosition().getX();
      float y = effect.getPosition().getY();
      unitSpawner.spawnUnit(
          x,
          y,
          effect.getTeam(),
          stats.getSpawnCharacter(),
          effect.getRarity(),
          effect.getLevel(),
          stats.getSpawnDeployTime());
      effect.setSpawnTriggered(true);
    }
  }

  /**
   * Processes a multi-entry spawn sequence (e.g. Graveyard spawns 13 Skeletons at predefined
   * offsets with staggered delays). Each entry fires when the accumulator reaches its spawnDelay.
   * Positions are mirrored based on arena placement side and team direction.
   */
  private void processSpawnSequence(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();
    List<SpawnSequenceEntry> sequence = stats.getSpawnSequence();
    TroopStats spawnChar = stats.getSpawnCharacter();
    if (spawnChar == null || effect.getNextSpawnIndex() >= sequence.size()) {
      return;
    }

    effect.setSpawnDelayAccumulator(effect.getSpawnDelayAccumulator() + deltaTime);
    float elapsed = effect.getSpawnDelayAccumulator();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    // Mirror X on right half of arena (width=18, midpoint=9)
    float xMirror = centerX > 9f ? -1f : 1f;
    // BLUE forward = +Y, RED forward = -Y
    float yMirror = effect.getTeam() == Team.BLUE ? 1f : -1f;

    while (effect.getNextSpawnIndex() < sequence.size()) {
      SpawnSequenceEntry entry = sequence.get(effect.getNextSpawnIndex());
      if (elapsed < entry.spawnDelay()) {
        break;
      }

      float spawnX = centerX + entry.relativeX() * xMirror;
      float spawnY = centerY + entry.relativeY() * yMirror;

      unitSpawner.spawnUnit(
          spawnX,
          spawnY,
          effect.getTeam(),
          spawnChar,
          effect.getRarity(),
          effect.getLevel(),
          stats.getSpawnDeployTime());

      effect.setNextSpawnIndex(effect.getNextSpawnIndex() + 1);
    }
  }

  /**
   * Processes a targeted area effect (Vines). Selects up to targetCount enemies with the highest
   * effective HP (current HP + shield) within radius, applying effects with staggered delays. After
   * all targets are selected, applies DOT damage ticks via the buff's hitFrequency.
   */
  private void processTargetedEffect(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();

    // Phase 1: Target selection with initial delay + staggered per-target delays
    if (effect.getNextTargetSelectionIndex() < stats.getTargetDelays().size()) {
      effect.setTargetSelectionAccumulator(effect.getTargetSelectionAccumulator() + deltaTime);
      float acc = effect.getTargetSelectionAccumulator();

      while (effect.getNextTargetSelectionIndex() < stats.getTargetDelays().size()) {
        float threshold =
            stats.getInitialDelay()
                + stats.getTargetDelays().get(effect.getNextTargetSelectionIndex());
        if (acc < threshold) {
          break;
        }

        // Select the next highest-HP target not yet selected
        Entity bestTarget = findHighestHpTarget(effect);
        if (bestTarget != null) {
          effect.getHitEntityIds().add(bestTarget.getId());

          // Apply freeze buff
          applyBuff(effect, bestTarget);

          // Air-to-ground conversion
          if (stats.isAirToGround()
              && bestTarget instanceof Troop troop
              && troop.getMovementType() == MovementType.AIR) {
            // Check the base movement type (before grounding), not the override
            // At this point the buff hasn't been applied yet in the movement type check,
            // so we check the original type from the entity definition
            if (troop.getMovement() != null && troop.getMovement().getType() == MovementType.AIR) {
              troop.setGroundedTimer(stats.getAirToGroundDuration());
            }
          }

          // Mark DOT as active on first target selection
          if (!effect.isDotActive()) {
            effect.setDotActive(true);
          }
        }

        effect.setNextTargetSelectionIndex(effect.getNextTargetSelectionIndex() + 1);
      }
    }

    // Phase 2: DOT ticking
    if (effect.isDotActive()) {
      BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
      float hitFrequency =
          (buffDef != null && buffDef.getHitFrequency() > 0) ? buffDef.getHitFrequency() : 1.0f;

      effect.setDotTickAccumulator(effect.getDotTickAccumulator() + deltaTime);
      effect.setDotElapsedTime(effect.getDotElapsedTime() + deltaTime);

      while (effect.getDotTickAccumulator() >= hitFrequency) {
        effect.setDotTickAccumulator(effect.getDotTickAccumulator() - hitFrequency);

        // Apply DOT damage to all selected targets still alive
        for (long targetId : effect.getHitEntityIds()) {
          Entity target = gameState.getEntityById(targetId).orElse(null);
          if (target == null || !target.isAlive()) {
            continue;
          }

          int damage = effect.getScaledDotDamage();
          if (target instanceof Tower) {
            damage =
                effect.getScaledCrownTowerDotDamage() > 0
                    ? effect.getScaledCrownTowerDotDamage()
                    : damage;
          }
          if (damage > 0) {
            target.getHealth().takeDamage(damage);
          }
        }
      }

      // Stop DOT when buff duration is exceeded
      if (effect.getDotElapsedTime() >= stats.getBuffDuration()) {
        effect.setDotActive(false);
      }
    }
  }

  /**
   * Processes the DarkMagic laser ball mechanic. After an initial delay, scans every scanInterval
   * seconds to count targets and select a damage tier, then applies exactly one hit per scan to all
   * locked targets.
   *
   * <p>The laser ball bypasses hitsGround/hitsAir (both are false in data) and directly damages all
   * alive enemy entities within radius.
   */
  private void processLaserBall(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();
    int totalScans = effect.getTotalLaserScans();

    // All scans completed -- effect can die naturally via update()
    if (totalScans > 0 && effect.getLaserScanCount() >= totalScans) {
      return;
    }

    // Phase 1: Delay before first scan
    if (!effect.isLaserActive()) {
      float acc = effect.getLaserDelayAccumulator() + deltaTime;
      if (acc < stats.getFirstHitDelay()) {
        effect.setLaserDelayAccumulator(acc);
        return;
      }
      // Activate and carry over excess time
      effect.setLaserActive(true);
      float excess = acc - stats.getFirstHitDelay();
      effect.setLaserDelayAccumulator(0f);

      // Perform the first scan and apply damage immediately
      performLaserScan(effect);
      applyLaserHit(effect);
      effect.setLaserScanCount(effect.getLaserScanCount() + 1);

      // Seed the scan accumulator with leftover time
      effect.setLaserScanAccumulator(excess);
    } else {
      effect.setLaserScanAccumulator(effect.getLaserScanAccumulator() + deltaTime);
    }

    // Phase 2: Fire scans at scanInterval boundaries
    float scanInterval = stats.getScanInterval();
    while (effect.getLaserScanAccumulator() >= scanInterval
        && effect.getLaserScanCount() < totalScans) {
      effect.setLaserScanAccumulator(effect.getLaserScanAccumulator() - scanInterval);
      performLaserScan(effect);
      applyLaserHit(effect);
      effect.setLaserScanCount(effect.getLaserScanCount() + 1);
    }
  }

  /**
   * Scans for enemy targets within radius, selects a damage tier based on target count, and locks
   * target IDs and per-tick damage values for subsequent ticks.
   */
  private void performLaserScan(AreaEffect effect) {
    List<Entity> targets = findLaserTargets(effect);
    int targetCount = targets.size();

    effect.getLaserTargetIds().clear();
    for (Entity target : targets) {
      effect.getLaserTargetIds().add(target.getId());
    }

    // Select damage tier: tiers are ordered by ascending maxTargets.
    // Use the first tier where targetCount <= maxTargets (and maxTargets > 0).
    // Last tier with maxTargets=0 is the catch-all.
    List<ScaledDamageTier> tiers = effect.getScaledDamageTiers();
    ScaledDamageTier selectedTier = null;
    if (!tiers.isEmpty()) {
      for (ScaledDamageTier tier : tiers) {
        if (tier.maxTargets() > 0 && targetCount <= tier.maxTargets()) {
          selectedTier = tier;
          break;
        }
        if (tier.maxTargets() == 0) {
          selectedTier = tier;
          break;
        }
      }
      if (selectedTier == null) {
        selectedTier = tiers.get(tiers.size() - 1);
      }
    }

    if (selectedTier != null) {
      effect.setLaserDamagePerHit(selectedTier.damagePerHit());
      effect.setLaserCtDamagePerHit(selectedTier.ctDamagePerHit());
    } else {
      effect.setLaserDamagePerHit(0);
      effect.setLaserCtDamagePerHit(0);
    }
  }

  /** Applies one laser hit of damage to all locked targets. Skips dead entities. */
  private void applyLaserHit(AreaEffect effect) {
    int damage = effect.getLaserDamagePerHit();
    int ctDamage = effect.getLaserCtDamagePerHit();
    if (damage <= 0 && ctDamage <= 0) {
      return;
    }

    for (long targetId : effect.getLaserTargetIds()) {
      Entity target = gameState.getEntityById(targetId).orElse(null);
      if (target == null || !target.isAlive()) {
        continue;
      }

      int effectiveDamage = damage;
      if (target instanceof Tower && ctDamage > 0) {
        effectiveDamage = ctDamage;
      }
      if (effectiveDamage > 0) {
        target.getHealth().takeDamage(effectiveDamage);
      }
    }
  }

  /**
   * Finds all alive enemy entities within the laser ball radius. Bypasses hitsGround/hitsAir checks
   * since the laser ball targets everything.
   */
  private List<Entity> findLaserTargets(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    List<Entity> targets = new ArrayList<>();
    for (Entity target : gameState.getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }

      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      targets.add(target);
    }
    return targets;
  }

  /**
   * Finds the enemy entity with the highest effective HP (current HP + shield HP) within the
   * effect's radius that has not already been selected. Excludes buildings (EntityType.BUILDING)
   * but includes towers.
   */
  private Entity findHighestHpTarget(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();

    Entity bestTarget = null;
    int bestEffectiveHp = -1;

    for (Entity target : gameState.getAliveEntities()) {
      if (target.getTeam() != enemyTeam) {
        continue;
      }
      if (!target.isTargetable()) {
        continue;
      }
      // Exclude deployed buildings (spawners, etc.) but include towers
      if (target.getEntityType() == EntityType.BUILDING) {
        continue;
      }
      if (effect.getHitEntityIds().contains(target.getId())) {
        continue;
      }

      // Check within radius (regardless of hitsGround/hitsAir since Vines has both false
      // but uses targeted selection instead)
      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      int effectiveHp = target.getHealth().getCurrent() + target.getHealth().getShield();
      if (effectiveHp > bestEffectiveHp) {
        bestEffectiveHp = effectiveHp;
        bestTarget = target;
      }
    }

    return bestTarget;
  }

  private void applyToTargets(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();

    // Clone spell: duplicate friendly troops in the area
    if (stats.isClone()) {
      applyClone(effect);
      return;
    }

    // Use the onlyEnemies field from the data to determine targeting
    if (stats.isOnlyEnemies()) {
      // Enemy targeting
      if (stats.isHitBiggestTargets()) {
        applyToBiggestTarget(effect);
      } else {
        applyDamageToEnemies(effect);
      }
    } else {
      // Friendly targeting (heals, buffs like Rage)
      BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
      if (buffDef != null && buffDef.getHealPerSecond() > 0) {
        applyHealToFriendlies(effect, buffDef);
      } else {
        applyBuffToFriendlies(effect);
      }
    }
  }

  /**
   * Applies damage and buffs to enemy entities within the area effect radius. Handles building
   * damage bonuses, crown tower damage reduction, and hidden building bypass.
   */
  private void applyDamageToEnemies(AreaEffect effect) {
    AreaEffectStats stats = effect.getStats();
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
      // Skip deployed buildings when ignoreBuildings is set (Royal Delivery).
      // Crown towers (EntityType.TOWER) are not affected by this flag.
      if (stats.isIgnoreBuildings() && target.getEntityType() == EntityType.BUILDING) {
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

          // Absolute crown tower damage for ticking AEOs (GoblinCurse)
          if (target instanceof Tower && effect.getScaledCrownTowerDotDamage() > 0) {
            effectiveDamage = effect.getScaledCrownTowerDotDamage();
          } else {
            // Then apply crown tower damage reduction
            effectiveDamage = DamageUtil.adjustForCrownTower(effectiveDamage, target, ctdp);
          }

          target.getHealth().takeDamage(effectiveDamage);
        }

        // Apply knockback if the effect has pushback (e.g. GoblinDrillDamage)
        if (stats.getPushback() > 0) {
          applyAreaEffectKnockback(target, centerX, centerY, stats.getPushback());
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

  private static final float KNOCKBACK_DURATION = 0.5f;
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  /**
   * Applies knockback to an entity hit by an area effect. Buildings and entities with
   * ignorePushback are immune.
   */
  private void applyAreaEffectKnockback(
      Entity target, float centerX, float centerY, float pushback) {
    Movement movement = target.getMovement();
    if (movement == null) {
      return;
    }
    if (movement.isBuilding() || movement.isIgnorePushback()) {
      return;
    }

    float dx = target.getPosition().getX() - centerX;
    float dy = target.getPosition().getY() - centerY;
    float dist = (float) Math.sqrt(dx * dx + dy * dy);
    float dirX = dist > 0.001f ? dx / dist : 0f;
    float dirY = dist > 0.001f ? dy / dist : 1f;

    movement.startKnockback(dirX, dirY, pushback, KNOCKBACK_DURATION, KNOCKBACK_MAX_TIME);
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

    boolean appliedStunOrFreeze = false;
    boolean appliedFreeze = false;

    for (BuffApplication buffApp : stats.getBuffApplications()) {
      StatusEffectType effectType = StatusEffectType.fromBuffName(buffApp.buffName());
      if (effectType == null) {
        continue;
      }

      // Buildings cannot be Cursed (GoblinCurse, VoodooCurse convention)
      if (effectType == StatusEffectType.CURSE
          && target.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      float duration = buffApp.duration() > 0 ? buffApp.duration() : 1.0f;
      if (stats.isCapBuffTimeToAreaEffectTime()) {
        duration = Math.min(duration, effect.getRemainingLifetime());
      }

      // Pass spawnSpecies for CURSE buffs so death-spawn triggers correctly
      if (effectType == StatusEffectType.CURSE && buffApp.curseSpawnStats() != null) {
        target.addEffect(
            new AppliedEffect(effectType, duration, buffApp.buffName(), buffApp.curseSpawnStats()));
      } else {
        target.addEffect(new AppliedEffect(effectType, duration, buffApp.buffName()));
      }

      if (effectType == StatusEffectType.STUN || effectType == StatusEffectType.FREEZE) {
        appliedStunOrFreeze = true;
      }
      if (effectType == StatusEffectType.FREEZE) {
        appliedFreeze = true;
      }
    }

    // Handle Stun/Freeze Reset Logic (Reset attack windup and charge ability)
    if (appliedStunOrFreeze) {
      Combat combat = target.getCombat();
      if (combat != null) {
        combat.resetAttackState();
      }
      // Reset charge ability state (Prince, Dark Prince, Battle Ram, Ram Rider)
      // Reset variable damage state (Inferno Dragon, Inferno Tower)
      if (target instanceof Troop troop) {
        ChargeHandler.consumeCharge(troop);
        VariableDamageHandler.resetVariableDamage(troop);
      } else if (target instanceof Building building && building.getAbility() != null) {
        VariableDamageHandler.resetVariableDamage(building.getAbility(), building.getCombat());
      }
    }

    // Freeze forces hidden buildings (Tesla) to reveal
    if (appliedFreeze && target instanceof Building building) {
      HidingHandler.forceRevealHiding(building);
    }
  }
}
