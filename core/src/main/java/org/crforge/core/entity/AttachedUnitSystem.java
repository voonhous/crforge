package org.crforge.core.entity;

import lombok.RequiredArgsConstructor;
import org.crforge.core.component.AttachedComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;

/**
 * Synchronizes attached units with their parent entities. Handles position syncing, parent death
 * propagation, and status effect inheritance from parent to child.
 *
 * <p>Attached units (e.g. Ram Rider on Ram, Spear Goblins on Goblin Giant) ride on their parent and
 * cannot be independently targeted. When the parent dies, all attached children die too.
 */
@RequiredArgsConstructor
public class AttachedUnitSystem {

  private final GameState gameState;

  /**
   * Updates all attached units: syncs position with parent, kills children when parent dies, and
   * propagates status effect modifiers from parent to child.
   */
  public void update(float deltaTime) {
    for (Entity entity : gameState.getAliveEntities()) {
      if (!(entity instanceof Troop troop) || !troop.isAttached()) {
        continue;
      }

      AttachedComponent attached = troop.getAttached();

      // Parent death -> kill the attached child
      if (!attached.isParentAlive()) {
        troop.getHealth().takeDamage(troop.getHealth().getMax());
        continue;
      }

      Entity parent = attached.getParent();

      // Position sync: apply offset relative to parent center, rotated by parent's facing angle
      float parentAngle = parent.getPosition().getRotation();
      float cos = (float) Math.cos(parentAngle);
      float sin = (float) Math.sin(parentAngle);
      float rotatedX = attached.getOffsetX() * cos - attached.getOffsetY() * sin;
      float rotatedY = attached.getOffsetX() * sin + attached.getOffsetY() * cos;

      troop
          .getPosition()
          .set(parent.getPosition().getX() + rotatedX, parent.getPosition().getY() + rotatedY);

      // Propagate status effect modifiers from parent to child
      propagateModifiers(parent, troop);
    }
  }

  /**
   * Copies the parent's STATUS_EFFECT modifier state to the attached child. This ensures that when
   * the parent is stunned/slowed/raged, the rider inherits the same modifiers.
   */
  private void propagateModifiers(Entity parent, Troop child) {
    Movement parentMove = parent.getMovement();
    Movement childMove = child.getMovement();
    Combat childCombat = child.getCombat();

    if (parentMove == null) {
      return;
    }

    // Propagate movement disable state
    boolean parentMovementDisabled =
        parentMove.getMovementDisableSources().contains(ModifierSource.STATUS_EFFECT);
    if (childMove != null) {
      childMove.setMovementDisabled(ModifierSource.STATUS_EFFECT, parentMovementDisabled);
      // Copy speed multiplier
      Float parentSpeedMult = parentMove.getSpeedMultipliers().get(ModifierSource.STATUS_EFFECT);
      if (parentSpeedMult != null) {
        childMove.setSpeedMultiplier(ModifierSource.STATUS_EFFECT, parentSpeedMult);
      } else {
        childMove.setSpeedMultiplier(ModifierSource.STATUS_EFFECT, 1.0f);
      }
    }

    // Propagate combat disable and attack speed
    Combat parentCombat = parent.getCombat();
    if (parentCombat != null && childCombat != null) {
      boolean parentCombatDisabled =
          parentCombat.getCombatDisableSources().contains(ModifierSource.STATUS_EFFECT);
      childCombat.setCombatDisabled(ModifierSource.STATUS_EFFECT, parentCombatDisabled);

      Float parentAtkMult =
          parentCombat.getAttackSpeedMultipliers().get(ModifierSource.STATUS_EFFECT);
      if (parentAtkMult != null) {
        childCombat.setAttackSpeedMultiplier(ModifierSource.STATUS_EFFECT, parentAtkMult);
      } else {
        childCombat.setAttackSpeedMultiplier(ModifierSource.STATUS_EFFECT, 1.0f);
      }

      // Stun propagation: reset attack state on the child when parent gets stunned
      if (parentCombatDisabled && childCombat.isAttacking()) {
        childCombat.resetAttackState();
      }
    }
  }
}
