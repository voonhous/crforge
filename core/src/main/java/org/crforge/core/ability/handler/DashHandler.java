package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.DashAbility;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/** Handles the DASH ability (Bandit, MegaKnight). */
public class DashHandler implements AbilityHandler {

  private static final float DASH_SPEED = 15f; // Tiles per second during dash movement
  private static final float KNOCKBACK_DURATION = 0.5f;
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  private final GameState gameState;

  public DashHandler(GameState gameState) {
    this.gameState = gameState;
  }

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Troop troop)) {
      return;
    }

    DashAbility data = (DashAbility) ability.getData();
    Combat combat = troop.getCombat();

    switch (ability.getDashState()) {
      case IDLE -> {
        if (combat == null || !combat.hasTarget()) {
          return;
        }
        Entity target = combat.getCurrentTarget();
        float distance =
            troop.getPosition().distanceTo(target.getPosition())
                - troop.getCollisionRadius()
                - target.getCollisionRadius();

        boolean inAcquisitionRange =
            distance >= data.dashMinRange() && distance <= data.dashMaxRange();
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
          // Immune during dash (only for troops with dashImmuneTime, e.g. Bandit)
          if (data.dashImmuneTime() > 0) {
            troop.setInvulnerable(true);
          }
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
          if (data.dashImmuneTime() > 0) {
            troop.setInvulnerable(false);
          }
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
   * Applies knockback to an entity hit by a dash landing. Buildings and entities with
   * ignorePushback are immune.
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
}
