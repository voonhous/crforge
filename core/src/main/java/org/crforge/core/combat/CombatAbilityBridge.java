package org.crforge.core.combat;

import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.Entity;

/**
 * Decouples the combat pipeline from ability handler implementations. CombatSystem,
 * ProjectileHitProcessor, AoeDamageService, and AreaEffectContext call this interface instead of
 * static methods on ChargeHandler, BuffAllyHandler, ReflectHandler, and VariableDamageHandler.
 */
public interface CombatAbilityBridge {

  /** Returns charge-boosted damage if the attacker is charged, otherwise returns baseDamage. */
  int getChargeDamage(Entity attacker, int baseDamage);

  /** Consumes the charge ability state on the attacker (e.g. after a charge impact). */
  void consumeCharge(Entity attacker);

  /** Computes GiantBuffer bonus damage for this attack (proc on every Nth hit). */
  int processGiantBuffHit(Entity attacker, Entity target, Combat combat);

  /** Returns the reflect damage amount if the target has an active REFLECT ability, else 0. */
  int getReflectDamage(Entity target);

  /** Applies reflect counter-damage from the reflector to the attacker. */
  void applyReflectDamage(
      Entity reflector, Entity attacker, int reflectDamage, AoeDamageService aoeDamageService);

  /**
   * Resets charge and variable damage abilities on stun/freeze. Called when a stun or freeze effect
   * is applied to an entity.
   */
  void resetAbilitiesOnStun(Entity target);
}
