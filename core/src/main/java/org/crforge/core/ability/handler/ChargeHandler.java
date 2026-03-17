package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ChargeAbility;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/** Handles the CHARGE ability (Prince, Dark Prince, Ram Rider mount). */
public class ChargeHandler implements AbilityHandler {

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Troop troop)) {
      return;
    }

    Combat combat = troop.getCombat();
    if (combat == null) {
      return;
    }

    // Charge builds from continuous uninterrupted movement:
    // - No target required, just needs to be moving
    // - Attacking, stuns, knockbacks, freezes all stop movement and reset charge
    // - Once charged, first attack deals bonus damage, then charge resets
    boolean isAttacking = combat.isAttacking();
    boolean isMoving = troop.getMovement().getEffectiveSpeed() > 0 && !isAttacking;

    if (isMoving) {
      // Build charge while moving
      ability.setChargeTimer(ability.getChargeTimer() + deltaTime);

      if (!ability.isCharged() && ability.getChargeTimer() >= ability.getChargeTime()) {
        ability.setCharged(true);
        // Apply speed multiplier via ABILITY_CHARGE source
        ChargeAbility charge = (ChargeAbility) ability.getData();
        troop
            .getMovement()
            .setSpeedMultiplier(ModifierSource.ABILITY_CHARGE, charge.speedMultiplier());
      }
    } else if (!ability.isCharged() && ability.getChargeTimer() > 0) {
      // Stopped moving before fully charged -- charge is lost, restart from zero
      ability.reset();
      troop.getMovement().clearModifiers(ModifierSource.ABILITY_CHARGE);
    }
  }

  /**
   * Called by CombatSystem after a charge unit deals its attack. Returns the damage to use for this
   * attack (charge damage if charged).
   */
  public static int getChargeDamage(AbilityComponent ability, int baseDamage) {
    if (ability != null
        && ability.getData() instanceof ChargeAbility charge
        && ability.isCharged()) {
      return charge.chargeDamage();
    }
    return baseDamage;
  }

  /** Called after a charge attack lands. Resets the charge state. */
  public static void consumeCharge(Troop troop) {
    AbilityComponent ability = troop.getAbility();
    if (ability != null && ability.getData() instanceof ChargeAbility) {
      ability.reset();
      troop.getMovement().clearModifiers(ModifierSource.ABILITY_CHARGE);
    }
  }
}
