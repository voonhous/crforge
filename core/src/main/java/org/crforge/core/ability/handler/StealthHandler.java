package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.StealthAbility;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/**
 * Updates stealth state for Royal Ghost. While not attacking, the fade timer ticks up; once it
 * exceeds the fade time, the unit becomes invisible. While attacking and invisible, a grace period
 * allows the ghost to stay invisible briefly before revealing.
 */
public class StealthHandler implements AbilityHandler {

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Troop troop)) {
      return;
    }

    StealthAbility data = (StealthAbility) ability.getData();
    Combat combat = troop.getCombat();
    boolean attacking = combat != null && combat.isAttacking();

    if (!attacking) {
      // Not attacking: tick fade timer toward invisibility
      ability.setStealthFadeTimer(ability.getStealthFadeTimer() + deltaTime);
      if (ability.getStealthFadeTimer() >= data.fadeTime()) {
        ability.setInvisible(true);
        // Reset reveal timer so next attack gets the full grace period
        ability.setStealthRevealTimer(0f);
      }
    } else {
      // Attacking: tick reveal timer; once grace period expires, become visible
      ability.setStealthRevealTimer(ability.getStealthRevealTimer() + deltaTime);
      if (ability.getStealthRevealTimer() >= data.attackGracePeriod()) {
        ability.setInvisible(false);
        // Reset fade timer so invisibility must be re-earned
        ability.setStealthFadeTimer(0f);
      }
    }
  }
}
