package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
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
 * entity. From the next such step on, the targeting component's strike-now byte is set, which lands
 * the charged hit on the first visit the entity attacks. An entity with no charge range at all
 * loses its charge outright rather than keeping it.
 *
 * <p>Standing still resets the charge: to zero when the entity has a charge range to build over and
 * to "no charge" when it has not, and the strike-now byte is cleared. The step counts, not the
 * distance actually covered: a step of a unit in the moving state still counts when the grid holds
 * it, and a stunned unit's step of 0 resets it though it stays in that state. An attack pushback, a
 * jump and clone setup are the exceptions: the entity is not walking, but the charge is left
 * exactly as it was.
 *
 * <p>This never runs for an entity whose charge is already inactive; the displacement returns
 * before it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "All five paths agree with the reference line for line. Held by the reference battles"
            + " card_Prince, card_DarkPrince and status_zap_stuns_prince_charge: the growth to a"
            + " full charge, the strike-now byte from the next step and the reset of a unit that"
            + " stops to attack. Not held: the charge range a buff gives an entity without one,"
            + " whose buffs are refused.")
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
   * @param owner the entity, whose pending flags this writes
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
          queries.setChargeStrike(true);
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
          clearChargeStrike(queries, chain);
        }
        if (component.getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
          chain.startCharging();
        }
      }
    } else if ((attackFlag & 1) != 0
        || owner.getState() == GridEntityState.JUMPING
        || owner.getState() == GridEntityState.CLONE_SETUP) {
      // An attack pushback, a jump and clone setup all leave the charge exactly as it was.
    } else if (config.chargeRange() != 0) {
      component.setChargeProgress(0);
      clearChargeStrike(queries, chain);
    } else {
      chain.mark("modifier_component");
      if (queries.hasModifierComponent() && queries.chargeRangeFromModifiers() != 0) {
        component.setChargeProgress(0);
      } else {
        component.setChargeProgress(MovementState.CHARGE_INACTIVE);
      }
      clearChargeStrike(queries, chain);
    }
    if (component.getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
      Displacement.markCharging(owner);
    }
  }

  /** Clears the targeting component's strike-now byte, whenever the entity carries one. */
  private static void clearChargeStrike(MovementQueries queries, MovementChain chain) {
    chain.mark("movement_byte");
    if (queries.targetingPresent()) {
      queries.setChargeStrike(false);
    }
  }
}
