package org.crforge.core.ability;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
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
    List<Entity> aliveEntities = gameState.getAliveEntities();
    for (Entity entity : aliveEntities) {
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

      switch (ability.getData().type()) {
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
        ChargeAbility charge = (ChargeAbility) ability.getData();
        troop.getMovement().setSpeedMultiplier(
            ModifierSource.ABILITY_CHARGE, charge.speedMultiplier());
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
    if (ability != null && ability.getData() instanceof ChargeAbility charge
        && ability.isCharged()) {
      return charge.chargeDamage();
    }
    return baseDamage;
  }

  /**
   * Called after a charge attack lands. Resets the charge state.
   */
  public static void consumeCharge(Troop troop) {
    AbilityComponent ability = troop.getAbility();
    if (ability != null && ability.getData() instanceof ChargeAbility) {
      ability.reset();
      troop.getMovement().clearModifiers(ModifierSource.ABILITY_CHARGE);
    }
  }

  /**
   * Resets variable damage (inferno) back to stage 0. Called when the troop is
   * stunned or frozen -- a core counter-play mechanic (see src_og.js line 2279).
   */
  public static void resetVariableDamage(Troop troop) {
    AbilityComponent ability = troop.getAbility();
    if (ability != null && ability.getData() instanceof VariableDamageAbility) {
      ability.setCurrentStage(0);
      ability.setStageTimer(0f);
      ability.setLastTargetId(-1);
      Combat combat = troop.getCombat();
      if (combat != null) {
        combat.setDamageOverride(ability.getCurrentStageDamage());
      }
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
    List<VariableDamageStage> stages = ((VariableDamageAbility) ability.getData()).stages();
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
    DashAbility data = (DashAbility) ability.getData();
    Combat combat = troop.getCombat();

    switch (ability.getDashState()) {
      case IDLE -> {
        if (combat == null || !combat.hasTarget()) {
          return;
        }
        Entity target = combat.getCurrentTarget();
        float distance = troop.getPosition().distanceTo(target.getPosition())
            - troop.getCollisionRadius() - target.getCollisionRadius();

        boolean inAcquisitionRange = distance >= data.dashMinRange()
            && distance <= data.dashMaxRange();
        boolean inDashableRange = distance <= data.dashMaxRange();

        // Acquire candidate when target enters [minRange, maxRange]
        if (inAcquisitionRange && !ability.isDashCandidateAcquired()) {
          ability.setDashCandidateAcquired(true);
        }

        // Target exceeded maxRange -- reset candidate and cooldown
        if (!inDashableRange) {
          ability.setDashCandidateAcquired(false);
          ability.setDashCooldownTimer(data.dashCooldown());
          troop.getMovement().clearModifiers(ModifierSource.ABILITY_DASH);
          combat.clearModifiers(ModifierSource.ABILITY_DASH);
          return;
        }

        // No candidate yet (target never entered acquisition range) -- walk normally
        if (!ability.isDashCandidateAcquired()) {
          return;
        }

        // Candidate acquired and within maxRange -- tick cooldown or dash
        if (ability.getDashCooldownTimer() > 0) {
          // Cooldown active -- hold position, tick cooldown
          ability.setDashCooldownTimer(ability.getDashCooldownTimer() - deltaTime);
          troop.getMovement().setMovementDisabled(ModifierSource.ABILITY_DASH, true);
          combat.setCombatDisabled(ModifierSource.ABILITY_DASH, true);
          return;
        }

        // Cooldown expired -- release hold and start dash
        troop.getMovement().clearModifiers(ModifierSource.ABILITY_DASH);
        combat.clearModifiers(ModifierSource.ABILITY_DASH);
        ability.setDashCandidateAcquired(false);

        {
          // Start dash toward target -- stop at collision boundary, not center
          ability.setDashState(AbilityComponent.DashState.DASHING);
          ability.setDashTimer(0f);
          float tx = target.getPosition().getX();
          float ty = target.getPosition().getY();
          float dx = tx - troop.getPosition().getX();
          float dy = ty - troop.getPosition().getY();
          float dist = (float) Math.sqrt(dx * dx + dy * dy);
          float stopDist = troop.getCollisionRadius() + target.getCollisionRadius();
          if (dist > stopDist) {
            float ratio = (dist - stopDist) / dist;
            tx = troop.getPosition().getX() + dx * ratio;
            ty = troop.getPosition().getY() + dy * ratio;
          }
          ability.setDashTargetX(tx);
          ability.setDashTargetY(ty);
          // Calculate dash speed: fixed-duration flight or constant speed
          float dashDistance = troop.getPosition().distanceTo(tx, ty);
          if (data.dashConstantTime() > 0 && dashDistance > 0) {
            ability.setDashSpeed(dashDistance / data.dashConstantTime());
          } else {
            ability.setDashSpeed(DASH_SPEED);
          }
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

        float dashSpeed = ability.getDashSpeed();
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
        float landTime = data.dashLandingTime() > 0 ? data.dashLandingTime() : 0.2f;

        if (ability.getDashTimer() >= landTime) {
          // Done landing, back to idle -- clear ABILITY_DASH source
          ability.setDashState(AbilityComponent.DashState.IDLE);
          ability.setDashCooldownTimer(data.dashCooldown());
          if (combat != null) {
            combat.clearModifiers(ModifierSource.ABILITY_DASH);
          }
        }
      }
    }
  }

  private static final float KNOCKBACK_DURATION = 0.5f;
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  private void applyDashDamage(Troop dasher, DashAbility data) {
    int damage = data.dashDamage();
    if (damage <= 0) {
      return;
    }

    float pushback = data.dashPushback();
    float radius = data.dashRadius();
    if (radius > 0) {
      // AOE dash damage (MegaKnight)
      for (Entity entity : gameState.getAliveEntities()) {
        if (entity.getTeam() == dasher.getTeam()) {
          continue;
        }
        if (!entity.isTargetable()) {
          continue;
        }
        float distSq = dasher.getPosition().distanceToSquared(entity.getPosition());
        float effectiveRadius = radius + entity.getCollisionRadius();
        if (distSq <= effectiveRadius * effectiveRadius) {
          entity.getHealth().takeDamage(damage);
          applyDashKnockback(dasher, entity, pushback);
        }
      }
    } else {
      // Single target dash damage (Bandit)
      Combat combat = dasher.getCombat();
      if (combat != null && combat.hasTarget()) {
        Entity target = combat.getCurrentTarget();
        target.getHealth().takeDamage(damage);
        applyDashKnockback(dasher, target, pushback);
      }
    }
  }

  /**
   * Applies knockback to an entity hit by a dash landing.
   * Buildings and entities with ignorePushback are immune.
   */
  private void applyDashKnockback(Troop dasher, Entity target, float pushback) {
    if (pushback <= 0) {
      return;
    }
    Movement movement = target.getMovement();
    if (movement == null) {
      return;
    }
    if (movement.isBuilding() || movement.isIgnorePushback()) {
      return;
    }

    float dx = target.getPosition().getX() - dasher.getPosition().getX();
    float dy = target.getPosition().getY() - dasher.getPosition().getY();
    float dist = (float) Math.sqrt(dx * dx + dy * dy);
    float dirX = dist > 0.001f ? dx / dist : 0f;
    float dirY = dist > 0.001f ? dy / dist : 1f;

    movement.startKnockback(dirX, dirY, pushback, KNOCKBACK_DURATION, KNOCKBACK_MAX_TIME);
  }

  // -- HOOK --

  private static final float DASH_SPEED = 15f; // Tiles per second during dash movement
  private static final float SPEED_BASE = 60.0f;

  private void updateHook(Troop troop, AbilityComponent ability, float deltaTime) {
    HookAbility data = (HookAbility) ability.getData();
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
        if (distance >= data.hookMinimumRange() && distance <= data.hookRange()) {
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
        if (ability.getHookTimer() >= data.hookLoadTime()) {
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

        float pullSpeed = data.hookDragBackSpeed() / SPEED_BASE;
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

        float selfSpeed = data.hookDragSelfSpeed() / SPEED_BASE;
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
    if (ability == null || !(ability.getData() instanceof ReflectAbility reflect)) {
      return 0;
    }
    return reflect.reflectDamage();
  }
}
