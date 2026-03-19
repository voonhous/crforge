package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.player.Team;

/**
 * Handles AOE (area-of-effect) damage application, damage dealing, and status effect application.
 * Extracted from CombatSystem to break the circular dependency between CombatSystem and
 * SpawnerSystem. Both systems can depend on this service without depending on each other.
 */
public class AoeDamageService {

  private final GameState gameState;
  private final CombatAbilityBridge abilityBridge;

  public AoeDamageService(GameState gameState, CombatAbilityBridge abilityBridge) {
    this.gameState = gameState;
    this.abilityBridge = abilityBridge;
  }

  /**
   * Filters effects by their applyAfterDamage flag. Returns only effects matching the given phase.
   */
  List<EffectStats> filterEffects(List<EffectStats> effects, boolean afterDamage) {
    if (effects == null || effects.isEmpty()) {
      return Collections.emptyList();
    }
    List<EffectStats> filtered = new ArrayList<>();
    for (EffectStats e : effects) {
      if (e.isApplyAfterDamage() == afterDamage) {
        filtered.add(e);
      }
    }
    return filtered;
  }

  public void dealDamage(Entity target, int damage) {
    if (target == null || !target.isAlive() || target.isInvulnerable()) {
      return;
    }
    target.getHealth().takeDamage(damage);
  }

  public void applyEffects(Entity target, List<EffectStats> effects) {
    if (target == null || !target.isAlive() || effects == null || effects.isEmpty()) {
      return;
    }

    for (EffectStats stats : effects) {
      // Buildings (MovementType.BUILDING) cannot be Cursed
      if (stats.getType() == StatusEffectType.CURSE
          && target.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      // Pass buffName and spawnSpecies (if any) to the AppliedEffect
      AppliedEffect effect =
          new AppliedEffect(
              stats.getType(), stats.getDuration(), stats.getBuffName(), stats.getSpawnSpecies());
      target.addEffect(effect);

      // Handle Stun/Freeze Reset Logic (Reset attack windup and charge ability)
      if (stats.getType() == StatusEffectType.STUN || stats.getType() == StatusEffectType.FREEZE) {
        Combat combat = target.getCombat();
        if (combat != null) {
          combat.resetAttackState();
        }
        abilityBridge.resetAbilitiesOnStun(target);
      }
    }
  }

  /**
   * Apply spell damage to all targetable enemies within radius of the given center point. Accounts
   * for entity size in the radius check. No crown tower damage reduction.
   */
  public void applySpellDamage(
      Team sourceTeam,
      float centerX,
      float centerY,
      int damage,
      float radius,
      List<EffectStats> effects) {
    applySpellDamage(sourceTeam, centerX, centerY, damage, radius, effects, 0);
  }

  /**
   * Apply spell damage to all targetable enemies within radius of the given center point. Accounts
   * for entity size in the radius check. Applies crown tower damage reduction to Towers.
   */
  public void applySpellDamage(
      Team sourceTeam,
      float centerX,
      float centerY,
      int damage,
      float radius,
      List<EffectStats> effects,
      int crownTowerDamagePercent) {
    if (radius > 0) {
      gameState.recordAoeDamage(centerX, centerY, radius, sourceTeam);
    }

    Team enemyTeam = sourceTeam.opposite();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam) {
        continue;
      }
      if (!entity.isTargetable()) {
        continue;
      }

      float distanceSq = entity.getPosition().distanceToSquared(centerX, centerY);

      // Use Collision Radius for spell AOE check (squared distance avoids sqrt)
      float effectiveRadius = radius + entity.getCollisionRadius();
      if (distanceSq <= effectiveRadius * effectiveRadius) {
        int effectiveDamage =
            DamageUtil.adjustForCrownTower(damage, entity, crownTowerDamagePercent);
        // Apply pre-damage effects (e.g. Curse)
        applyEffects(entity, filterEffects(effects, false));
        dealDamage(entity, effectiveDamage);
        // Apply post-damage effects (e.g. slow, stun)
        applyEffects(entity, filterEffects(effects, true));
      }
    }
  }
}
