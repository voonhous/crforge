package org.crforge.core.entity.effect;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.EffectStats;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;

/**
 * Processes all active AreaEffect entities each tick, applying damage and buffs
 * to enemies within the effect radius.
 *
 * <p>One-shot effects (hitSpeed <= 0): apply once on first tick, then expire.
 * <p>Ticking effects (hitSpeed > 0): apply every hitSpeed seconds for the duration.
 */
@RequiredArgsConstructor
public class AreaEffectSystem {

  private final GameState gameState;

  /**
   * Update all active area effects. Should be called once per tick.
   */
  public void update(float deltaTime) {
    List<AreaEffect> effects = gameState.getEntitiesOfType(AreaEffect.class);
    for (AreaEffect effect : effects) {
      if (!effect.isAlive()) {
        continue;
      }
      processEffect(effect, deltaTime);
    }
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
    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();
    int damage = effect.getEffectiveDamage();

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

      float distance = target.getPosition().distanceTo(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();

      if (distance <= effectiveRadius) {
        // Apply buff first (before damage, consistent with CombatSystem convention)
        applyBuff(effect, target);

        // Apply damage with crown tower modifier
        if (damage > 0) {
          int effectiveDamage = adjustForCrownTower(damage, target, stats.getCrownTowerDamagePercent());
          target.getHealth().takeDamage(effectiveDamage);
        }
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

    // Use EffectStats intensity defaults (0 = use system default for that type)
    float duration = stats.getBuffDuration() > 0 ? stats.getBuffDuration() : 1.0f;
    target.addEffect(new AppliedEffect(effectType, duration, 0f));
  }

  private int adjustForCrownTower(int baseDamage, Entity target, int crownTowerDamagePercent) {
    if (crownTowerDamagePercent == 0 || !(target instanceof Tower)) {
      return baseDamage;
    }
    return Math.max(1, baseDamage * (100 + crownTowerDamagePercent) / 100);
  }
}
