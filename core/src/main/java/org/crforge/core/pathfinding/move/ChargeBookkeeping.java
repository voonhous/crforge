package org.crforge.core.pathfinding.move;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * What happens to a charge after a displacement: it grows while the entity walks, and it is dropped
 * whenever the entity does anything else.
 *
 * <p>The charge only ever builds while the entity actually covered ground in the moving state. It
 * grows by {@code step * 1000 / chargeRange}, so an entity charges over exactly the configured
 * range, and at 10000 it is complete: the completion action runs and the charging flag goes on the
 * entity. An entity with no charge range at all loses its charge outright rather than keeping it.
 *
 * <p>Standing still, an attack pushback, a jump and clone setup each reset the charge. The reset is
 * to zero when the entity has a charge range to build over and to "no charge" when it has not.
 *
 * <p>This never runs for an entity whose charge is already inactive; the displacement returns
 * before it.
 */
public final class ChargeBookkeeping {

  /** Scale the charge progress is expressed in relative to the configured charge range. */
  private static final int CHARGE_SCALE = 1000;

  private ChargeBookkeeping() {
    // Utility class
  }

  /**
   * Updates the charge progress after one displacement.
   *
   * @param component the entity's movement component, whose charge progress this writes
   * @param owner the entity, whose movement byte and pending flags this writes
   * @param config the entity's movement configuration columns
   * @param queries the answers the bookkeeping pulls from the rest of the simulation
   * @param chain the chain that records what the bookkeeping announced
   * @param step the distance the displacement was allowed to cover, in game units
   * @param attackFlag 1 when the step was an attack pushback
   */
  public static void postMove(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementQueries queries,
      MovementChain chain,
      int step,
      int attackFlag) {
    if (step >= 1 && owner.getState() == GridEntityState.MOVING) {
      if (component.getChargeProgress() > MovementState.CHARGE_COMPLETE - 1) {
        chain.mark("targeting_lookup");
        if (queries.targetingLookup()) {
          chain.mark("targeting_lookup");
          owner.setMovingMarker(1);
        }
      } else {
        int range = config.chargeRange();
        if (range == 0) {
          chain.mark("modifier_component");
          range = queries.hasModifierComponent() ? queries.chargeRangeFromModifiers() : 0;
        }
        if (range != 0) {
          component.setChargeProgress(
              component.getChargeProgress()
                  + FixedMath.divOrZero(Math.max(step, 0) * CHARGE_SCALE, range));
        } else {
          chain.mark("modifier_component");
          component.setChargeProgress(MovementState.CHARGE_INACTIVE);
          resetMovementByte(owner, queries, chain);
        }
        if (component.getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
          chain.mark("start_charging");
        }
      }
    } else if ((attackFlag & 1) != 0
        || owner.getState() == GridEntityState.JUMPING
        || owner.getState() == GridEntityState.CLONE_SETUP) {
      // An attack pushback, a jump and clone setup all leave the charge exactly as it was.
    } else if (config.chargeRange() != 0) {
      component.setChargeProgress(0);
      resetMovementByte(owner, queries, chain);
    } else {
      chain.mark("modifier_component");
      if (queries.hasModifierComponent() && queries.chargeRangeFromModifiers() != 0) {
        component.setChargeProgress(0);
      } else {
        component.setChargeProgress(MovementState.CHARGE_INACTIVE);
      }
      resetMovementByte(owner, queries, chain);
    }
    if (component.getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
      Displacement.markCharging(owner);
    }
  }

  /** Clears the entity's movement byte, which the targeting side reads. */
  private static void resetMovementByte(
      GridEntity owner, MovementQueries queries, MovementChain chain) {
    chain.mark("movement_byte");
    if (queries.targetingPresent()) {
      owner.setMovingMarker(0);
    }
  }
}
