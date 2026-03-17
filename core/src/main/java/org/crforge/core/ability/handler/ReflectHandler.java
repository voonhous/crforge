package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ReflectAbility;
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
    return reflect.reflectDamage();
  }
}
