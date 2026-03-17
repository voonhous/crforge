package org.crforge.core.entity.effect.handler;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.ScaledDamageTier;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;

/**
 * Processes the DarkMagic laser ball mechanic. After an initial delay, scans every scanInterval
 * seconds to count targets and select a damage tier, then applies exactly one hit per scan to all
 * locked targets.
 *
 * <p>The laser ball bypasses hitsGround/hitsAir (both are false in data) and directly damages all
 * alive enemy entities within radius.
 */
public class LaserBallHandler {

  private final GameState gameState;

  public LaserBallHandler(GameState gameState) {
    this.gameState = gameState;
  }

  public void process(AreaEffect effect, float deltaTime) {
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
}
