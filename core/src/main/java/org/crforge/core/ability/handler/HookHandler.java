package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.HookAbility;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.util.GameUnits;

/** Handles the HOOK ability (Fisherman). */
public class HookHandler implements AbilityHandler {

  private final GameState gameState;

  public HookHandler(GameState gameState) {
    this.gameState = gameState;
  }

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Troop troop)) {
      return;
    }

    HookAbility data = (HookAbility) ability.getData();
    Combat combat = troop.getCombat();

    switch (ability.getHookState()) {
      case IDLE -> {
        if (combat == null || !combat.hasTarget()) {
          return;
        }
        Entity target = combat.getCurrentTarget();
        // Hook triggers when the edge-to-edge distance is in [minimumRange, range]; compared as
        // exact center-to-center squared distances
        long distSq = troop.getPosition().distanceSquaredTo(target.getPosition());
        long radii = (long) troop.getCollisionRadius() + target.getCollisionRadius();
        if (!GameUnits.insideRadius(distSq, Math.max(0L, data.hookMinimumRange() + radii))
            && GameUnits.withinRadius(distSq, data.hookRange() + radii)) {
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
        float dist = hookerPos.distance(targetPos);
        float pullSpeed = GameUnits.rawSpeedToUnitsPerSecond(data.hookDragBackSpeed());
        float moveAmount = pullSpeed * deltaTime;

        if (dist
            <= moveAmount
                + Position.MAX_ROUNDING_DISTANCE
                + troop.getCollisionRadius()
                + target.getCollisionRadius()) {
          // Target arrived -- start dragging self (or just finish)
          ability.setHookState(AbilityComponent.HookState.DRAGGING_SELF);
          ability.setHookTimer(0f);
        } else {
          float nx = dx / dist;
          float ny = dy / dist;
          targetPos.move(nx * moveAmount, ny * moveAmount);
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
        float dist = hookerPos.distance(targetPos);
        float selfSpeed = GameUnits.rawSpeedToUnitsPerSecond(data.hookDragSelfSpeed());
        float moveAmount = selfSpeed * deltaTime;

        if (dist
            <= moveAmount
                + Position.MAX_ROUNDING_DISTANCE
                + troop.getCollisionRadius()
                + target.getCollisionRadius()) {
          // Done -- back to idle
          resetHook(troop, ability);
        } else {
          float nx = dx / dist;
          float ny = dy / dist;
          hookerPos.move(nx * moveAmount, ny * moveAmount);
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
}
