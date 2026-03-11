package org.crforge.core.entity.effect;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.combat.DamageUtil;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
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
    // Check if this area effect heals friendlies instead of damaging enemies
    AreaEffectStats stats = effect.getStats();
    BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
    if (buffDef != null && buffDef.getHealPerSecond() > 0) {
      applyHealToFriendlies(effect, buffDef);
      return;
    }

    Team enemyTeam = effect.getTeam().opposite();
    float centerX = effect.getPosition().getX();
    float centerY = effect.getPosition().getY();
    int damage = effect.getEffectiveDamage();
    int ctdp = effect.getEffectiveCrownTowerDamagePercent();

    List<Entity> aliveEntities = gameState.getAliveEntities();
    for (Entity target : aliveEntities) {
      if (target.getTeam() != enemyTeam) {
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
        // Apply buff first (before damage, consistent with CombatSystem convention)
        applyBuff(effect, target);

        // Apply damage with crown tower and building damage modifiers
        if (damage > 0) {
          int effectiveDamage = DamageUtil.adjustForCrownTower(damage, target, ctdp);

          // Apply building damage percent bonus (e.g. Earthquake deals 3.5x to buildings)
          if (effect.getBuildingDamagePercent() > 0
              && target.getEntityType() == EntityType.BUILDING) {
            effectiveDamage = effectiveDamage * effect.getBuildingDamagePercent() / 100;
          }

          target.getHealth().takeDamage(effectiveDamage);
        }
      }
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

      float distanceSq = target.getPosition().distanceToSquared(centerX, centerY);
      float effectiveRadius = stats.getRadius() + target.getCollisionRadius();
      if (distanceSq <= effectiveRadius * effectiveRadius) {
        target.getHealth().heal(healAmount);
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
      }
    }
  }
}
