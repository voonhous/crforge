package org.crforge.core.ability.handler;

import java.util.List;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.card.EffectStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.DamageUtil;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/** Handles the REFLECT ability. Reflect is passive -- no per-tick update needed. */
public class ReflectHandler implements AbilityHandler {

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    // Reflect is passive -- handled in CombatSystem.dealDamage()
  }

  /**
   * Called by CombatSystem when a melee attacker hits an entity with REFLECT ability. Returns the
   * reflect damage to deal back to the attacker, or 0 if no reflect.
   */
  public static int getReflectDamage(Troop target) {
    AbilityComponent ability = target.getAbility();
    if (ability == null || !(ability.getData() instanceof ReflectAbility reflect)) {
      return 0;
    }
    int scaled = ability.getScaledReflectDamage();
    return scaled > 0 ? scaled : reflect.reflectDamage();
  }

  /**
   * Applies reflect counter-damage and any reflect buff (e.g. ZapFreeze stun) to the attacker.
   * Shared by CombatSystem (melee reflect) and ProjectileHitProcessor (ranged reflect).
   */
  public static void applyReflectDamage(
      Troop reflector, Entity attacker, int reflectDamage, AoeDamageService aoeDamageService) {
    ReflectAbility reflect = (ReflectAbility) reflector.getAbility().getData();
    int effectiveDamage =
        DamageUtil.adjustForCrownTower(
            reflectDamage, attacker, reflect.reflectCrownTowerDamagePercent());
    aoeDamageService.dealDamage(attacker, effectiveDamage);

    // Apply reflect buff (e.g. ZapFreeze stun) to attacker
    if (reflect.reflectBuff() != null && reflect.reflectBuffDuration() > 0) {
      EffectStats reflectEffect =
          EffectStats.builder()
              .type(reflect.reflectBuff())
              .duration(reflect.reflectBuffDuration())
              .buffName(reflect.reflectBuffName())
              .build();
      aoeDamageService.applyEffects(attacker, List.of(reflectEffect));
    }
  }
}
