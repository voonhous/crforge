package org.crforge.core.ability;

import org.crforge.core.ability.handler.BuffAllyHandler;
import org.crforge.core.ability.handler.ChargeHandler;
import org.crforge.core.ability.handler.ReflectHandler;
import org.crforge.core.ability.handler.VariableDamageHandler;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatAbilityBridge;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;

/**
 * Default implementation that delegates to the existing static ability handlers. Serves as the
 * bridge between the combat pipeline and the ability system without introducing direct
 * dependencies.
 */
public class DefaultCombatAbilityBridge implements CombatAbilityBridge {

  @Override
  public int getChargeDamage(Entity attacker, int baseDamage) {
    if (attacker instanceof Troop troop) {
      return ChargeHandler.getChargeDamage(troop.getAbility(), baseDamage);
    }
    return baseDamage;
  }

  @Override
  public void consumeCharge(Entity attacker) {
    if (attacker instanceof Troop troop) {
      ChargeHandler.consumeCharge(troop);
    }
  }

  @Override
  public int processGiantBuffHit(Entity attacker, Entity target, Combat combat) {
    if (attacker instanceof Troop buffedTroop) {
      return BuffAllyHandler.processGiantBuffHit(buffedTroop, target, combat);
    }
    return 0;
  }

  @Override
  public int getReflectDamage(Entity target) {
    if (target instanceof Troop troop) {
      return ReflectHandler.getReflectDamage(troop);
    }
    return 0;
  }

  @Override
  public void applyReflectDamage(
      Entity reflector, Entity attacker, int reflectDamage, AoeDamageService aoeDamageService) {
    if (reflector instanceof Troop troop) {
      ReflectHandler.applyReflectDamage(troop, attacker, reflectDamage, aoeDamageService);
    }
  }

  @Override
  public void resetAbilitiesOnStun(Entity target) {
    // Reset charge ability state (Prince, Dark Prince, Battle Ram, Ram Rider)
    // Reset variable damage state (Inferno Dragon, Inferno Tower)
    if (target instanceof Troop troop) {
      ChargeHandler.consumeCharge(troop);
      VariableDamageHandler.resetVariableDamage(troop);
    } else if (target instanceof Building building && building.getAbility() != null) {
      VariableDamageHandler.resetVariableDamage(building.getAbility(), building.getCombat());
    }
  }
}
