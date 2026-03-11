package org.crforge.core.ability;

import org.crforge.core.effect.StatusEffectType;

/**
 * Reflect ability data (Electro Giant). When a melee attacker hits this troop within the reflect
 * radius, counter-damage and an optional debuff are applied to the attacker.
 *
 * @param reflectDamage damage reflected back to the attacker
 * @param reflectRadius radius within which reflect triggers
 * @param reflectBuff status effect applied to the attacker (e.g. STUN)
 * @param reflectBuffDuration duration of the reflected status effect
 * @param reflectCrownTowerDamagePercent percentage of reflect damage dealt to crown towers
 * @param reflectBuffName original buff name for BuffDefinition lookup
 */
public record ReflectAbility(
    int reflectDamage,
    float reflectRadius,
    StatusEffectType reflectBuff,
    float reflectBuffDuration,
    int reflectCrownTowerDamagePercent,
    String reflectBuffName)
    implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.REFLECT;
  }
}
