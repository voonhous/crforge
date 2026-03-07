package org.crforge.core.ability;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;

/**
 * Processes ability updates each tick. Handles charge buildup, variable damage
 * (inferno) stage escalation, dash, hook, and reflect.
 *
 * <p>Should run before CombatSystem in the tick loop so damage modifications
 * take effect on the current tick's attacks.
 */
public class AbilitySystem {

  private final GameState gameState;

  public AbilitySystem(GameState gameState) {
    this.gameState = gameState;
  }

  public void update(float deltaTime) {
    for (Entity entity : gameState.getAliveEntities()) {
      if (!(entity instanceof Troop troop)) {
        continue;
      }
      AbilityComponent ability = troop.getAbility();
      if (ability == null) {
        continue;
      }
      if (troop.isDeploying()) {
        continue;
      }

      switch (ability.getData().getType()) {
        case CHARGE -> updateCharge(troop, ability, deltaTime);
        case VARIABLE_DAMAGE -> updateVariableDamage(troop, ability, deltaTime);
        case DASH -> updateDash(troop, ability, deltaTime);
        case HOOK -> updateHook(troop, ability, deltaTime);
        case REFLECT -> {
          // Reflect is passive -- handled in CombatSystem.dealDamage()
        }
      }
    }
  }

  private void updateCharge(Troop troop, AbilityComponent ability, float deltaTime) {
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
        troop.getMovement().setSpeedMultiplier(
            ModifierSource.ABILITY_CHARGE, ability.getData().getSpeedMultiplier());
      }
    } else if (!ability.isCharged() && ability.getChargeTimer() > 0) {
      // Stopped moving before fully charged -- charge is lost, restart from zero
      ability.reset();
      troop.getMovement().clearModifiers(ModifierSource.ABILITY_CHARGE);
    }
  }

  /**
   * Called by CombatSystem after a charge unit deals its attack.
   * Returns the damage to use for this attack (charge damage if charged).
   */
  public static int getChargeDamage(AbilityComponent ability, int baseDamage) {
    if (ability != null && ability.getData().getType() == AbilityType.CHARGE
        && ability.isCharged()) {
      return ability.getData().getChargeDamage();
    }
    return baseDamage;
  }

  /**
   * Called after a charge attack lands. Resets the charge state.
   */
  public static void consumeCharge(Troop troop) {
    AbilityComponent ability = troop.getAbility();
    if (ability != null && ability.getData().getType() == AbilityType.CHARGE) {
      ability.reset();
      troop.getMovement().clearModifiers(ModifierSource.ABILITY_CHARGE);
    }
  }

  private void updateVariableDamage(Troop troop, AbilityComponent ability, float deltaTime) {
    Combat combat = troop.getCombat();
    if (combat == null) {
      return;
    }

    // Check if target changed -- reset on retarget
    long currentTargetId = combat.hasTarget() ? combat.getCurrentTarget().getId() : -1;
    if (currentTargetId != ability.getLastTargetId()) {
      ability.setCurrentStage(0);
      ability.setStageTimer(0f);
      ability.setLastTargetId(currentTargetId);
    }

    // No target -- stay at stage 0
    if (!combat.hasTarget()) {
      return;
    }

    // Accumulate time and advance stages
    List<VariableDamageStage> stages = ability.getData().getStages();
    if (stages.isEmpty()) {
      return;
    }

    ability.setStageTimer(ability.getStageTimer() + deltaTime);

    // Check if we should advance to the next stage
    int stage = ability.getCurrentStage();
    if (stage < stages.size() - 1) {
      VariableDamageStage nextStage = stages.get(stage + 1);
      if (nextStage.durationSeconds() > 0 && ability.getStageTimer() >= nextStage.durationSeconds()) {
        ability.setStageTimer(ability.getStageTimer() - nextStage.durationSeconds());
        ability.setCurrentStage(stage + 1);
      }
    }

    // Apply current stage damage to combat component
    combat.setDamageOverride(ability.getCurrentStageDamage());
  }

  // -- DASH --

  private void updateDash(Troop troop, AbilityComponent ability, float deltaTime) {
    AbilityData data = ability.getData();
    Combat combat = troop.getCombat();

    // Tick cooldown
    if (ability.getDashCooldownTimer() > 0) {
      ability.setDashCooldownTimer(ability.getDashCooldownTimer() - deltaTime);
    }

    switch (ability.getDashState()) {
      case IDLE -> {
        // Check if we should start a dash
        if (combat == null || !combat.hasTarget() || ability.getDashCooldownTimer() > 0) {
          return;
        }
        Entity target = combat.getCurrentTarget();
        float distance = troop.getPosition().distanceTo(target.getPosition())
            - troop.getCollisionRadius() - target.getCollisionRadius();

        if (distance >= data.getDashMinRange() && distance <= data.getDashMaxRange()) {
          // Start dash toward target
          ability.setDashState(AbilityComponent.DashState.DASHING);
          ability.setDashTimer(0f);
          ability.setDashTargetX(target.getPosition().getX());
          ability.setDashTargetY(target.getPosition().getY());
          // Immune during dash
          troop.setInvulnerable(true);
          // Disable normal combat during dash (set once, survives StatusEffectSystem)
          if (combat != null) {
            combat.setCombatDisabled(ModifierSource.ABILITY_DASH, true);
          }
        }
      }
      case DASHING -> {
        ability.setDashTimer(ability.getDashTimer() + deltaTime);

        // Move toward dash target at high speed
        Position pos = troop.getPosition();
        float dx = ability.getDashTargetX() - pos.getX();
        float dy = ability.getDashTargetY() - pos.getY();
        float dist = pos.distanceTo(ability.getDashTargetX(), ability.getDashTargetY());

        float dashSpeed = 15f; // Fast dash movement
        float moveAmount = dashSpeed * deltaTime;

        if (dist <= moveAmount || dist < 0.1f) {
          // Arrived at target -- transition to landing
          pos.set(ability.getDashTargetX(), ability.getDashTargetY());
          ability.setDashState(AbilityComponent.DashState.LANDING);
          ability.setDashTimer(0f);

          // Deal dash damage
          applyDashDamage(troop, data);

          // End immunity
          troop.setInvulnerable(false);
        } else {
          // Move toward target
          float nx = dx / dist;
          float ny = dy / dist;
          pos.set(pos.getX() + nx * moveAmount, pos.getY() + ny * moveAmount);
        }
      }
      case LANDING -> {
        ability.setDashTimer(ability.getDashTimer() + deltaTime);
        float landTime = data.getDashLandingTime() > 0 ? data.getDashLandingTime() : 0.2f;

        if (ability.getDashTimer() >= landTime) {
          // Done landing, back to idle -- clear ABILITY_DASH source
          ability.setDashState(AbilityComponent.DashState.IDLE);
          ability.setDashCooldownTimer(data.getDashCooldown());
          if (combat != null) {
            combat.clearModifiers(ModifierSource.ABILITY_DASH);
          }
        }
      }
    }
  }

  private void applyDashDamage(Troop dasher, AbilityData data) {
    int damage = data.getDashDamage();
    if (damage <= 0) {
      return;
    }

    float radius = data.getDashRadius();
    if (radius > 0) {
      // AOE dash damage (MegaKnight)
      for (Entity entity : gameState.getAliveEntities()) {
        if (entity.getTeam() == dasher.getTeam()) {
          continue;
        }
        if (!entity.isTargetable()) {
          continue;
        }
        float dist = dasher.getPosition().distanceTo(entity.getPosition());
        if (dist <= radius + entity.getCollisionRadius()) {
          entity.getHealth().takeDamage(damage);
        }
      }
    } else {
      // Single target dash damage (Bandit)
      Combat combat = dasher.getCombat();
      if (combat != null && combat.hasTarget()) {
        combat.getCurrentTarget().getHealth().takeDamage(damage);
      }
    }
  }

  // -- HOOK --

  private static final float SPEED_BASE = 60.0f;

  private void updateHook(Troop troop, AbilityComponent ability, float deltaTime) {
    AbilityData data = ability.getData();
    Combat combat = troop.getCombat();

    switch (ability.getHookState()) {
      case IDLE -> {
        if (combat == null || !combat.hasTarget()) {
          return;
        }
        Entity target = combat.getCurrentTarget();
        float distance = troop.getPosition().distanceTo(target.getPosition())
            - troop.getCollisionRadius() - target.getCollisionRadius();

        // Hook triggers when target is in [minimumRange, range]
        if (distance >= data.getHookMinimumRange() && distance <= data.getHookRange()) {
          ability.setHookState(AbilityComponent.HookState.WINDING_UP);
          ability.setHookTimer(0f);
          ability.setHookedTargetId(target.getId());
          // Set once -- source-tracked, won't be trampled by StatusEffectSystem
          if (combat != null) {
            combat.setCombatDisabled(ModifierSource.ABILITY_HOOK, true);
          }
          troop.getMovement().setMovementDisabled(ModifierSource.ABILITY_HOOK, true);
        }
      }
      case WINDING_UP -> {
        // No re-assert needed -- ABILITY_HOOK source persists across StatusEffectSystem resets
        ability.setHookTimer(ability.getHookTimer() + deltaTime);
        if (ability.getHookTimer() >= data.getHookLoadTime()) {
          Entity target = findEntityById(ability.getHookedTargetId());
          if (target == null || !target.isAlive()) {
            resetHook(troop, ability);
            return;
          }
          // Buildings can't be pulled -- skip straight to dragging self toward them
          if (target instanceof Building) {
            ability.setHookState(AbilityComponent.HookState.DRAGGING_SELF);
          } else {
            ability.setHookState(AbilityComponent.HookState.PULLING);
          }
          ability.setHookTimer(0f);
        }
      }
      case PULLING -> {
        // No re-assert needed -- ABILITY_HOOK source persists across StatusEffectSystem resets

        // Pull target toward the fisherman
        Entity target = findEntityById(ability.getHookedTargetId());
        if (target == null || !target.isAlive()) {
          resetHook(troop, ability);
          return;
        }

        Position hookerPos = troop.getPosition();
        Position targetPos = target.getPosition();

        float dx = hookerPos.getX() - targetPos.getX();
        float dy = hookerPos.getY() - targetPos.getY();
        float dist = hookerPos.distanceTo(targetPos);

        float pullSpeed = data.getHookDragBackSpeed() / SPEED_BASE;
        float moveAmount = pullSpeed * deltaTime;

        if (dist <= moveAmount + troop.getCollisionRadius() + target.getCollisionRadius()) {
          // Target arrived -- start dragging self (or just finish)
          ability.setHookState(AbilityComponent.HookState.DRAGGING_SELF);
          ability.setHookTimer(0f);
        } else {
          float nx = dx / dist;
          float ny = dy / dist;
          targetPos.set(targetPos.getX() + nx * moveAmount, targetPos.getY() + ny * moveAmount);
        }
      }
      case DRAGGING_SELF -> {
        // No re-assert needed -- ABILITY_HOOK source persists across StatusEffectSystem resets

        // Fisherman pulls self toward the hooked target
        Entity target = findEntityById(ability.getHookedTargetId());
        if (target == null || !target.isAlive()) {
          resetHook(troop, ability);
          return;
        }

        Position hookerPos = troop.getPosition();
        Position targetPos = target.getPosition();

        float dx = targetPos.getX() - hookerPos.getX();
        float dy = targetPos.getY() - hookerPos.getY();
        float dist = hookerPos.distanceTo(targetPos);

        float selfSpeed = data.getHookDragSelfSpeed() / SPEED_BASE;
        float moveAmount = selfSpeed * deltaTime;

        if (dist <= moveAmount + troop.getCollisionRadius() + target.getCollisionRadius()) {
          // Done -- back to idle
          resetHook(troop, ability);
        } else {
          float nx = dx / dist;
          float ny = dy / dist;
          hookerPos.set(hookerPos.getX() + nx * moveAmount, hookerPos.getY() + ny * moveAmount);
        }
      }
    }
  }

  private void resetHook(Troop troop, AbilityComponent ability) {
    ability.setHookState(AbilityComponent.HookState.IDLE);
    ability.setHookTimer(0f);
    ability.setHookedTargetId(-1);
    // Clear ABILITY_HOOK source from both components
    Combat combat = troop.getCombat();
    if (combat != null) {
      combat.clearModifiers(ModifierSource.ABILITY_HOOK);
    }
    troop.getMovement().clearModifiers(ModifierSource.ABILITY_HOOK);
  }

  private Entity findEntityById(long id) {
    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getId() == id) {
        return entity;
      }
    }
    return null;
  }

  // -- REFLECT utility --

  /**
   * Called by CombatSystem when a melee attacker hits an entity with REFLECT ability.
   * Returns the reflect damage to deal back to the attacker, or 0 if no reflect.
   */
  public static int getReflectDamage(Troop target) {
    AbilityComponent ability = target.getAbility();
    if (ability == null || ability.getData().getType() != AbilityType.REFLECT) {
      return 0;
    }
    return ability.getData().getReflectDamage();
  }
}
