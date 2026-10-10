/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The pull of an attracting buff, added to an entity's push accumulators for its next movement
 * visit, as an area effect such as the Tornado asks once per hit.
 *
 * <p>The vector points from the entity to the centre of the pull. Its length is turned into a
 * direction by the integer square root, at least 1; an entity standing exactly on the centre is
 * pulled one unit straight toward its own back line. The radial share is AttractPercentage of the
 * entity's configured speed, scaled by PushSpeedFactor when that is set, and the perpendicular
 * share LateralPushPercentage of it; a positive PushMassFactor weakens both by the entity's mass,
 * at most to nothing. Each share is taken in hundredths, and the push along the direction is added
 * to the accumulators. It is the configured speed, not the buffed speed or the route step, so a
 * slower unit is pulled less, and the pull does not stop at the centre.
 *
 * <p>The pull counts as one more push, so collision pushes share the average with it. It lifts the
 * 150-unit cap on the averaged push, and for a unit that is neither flying nor hovering it asks the
 * grid move to keep it off the water. A jumping unit, and a dashing one whose row jumps, is not
 * pulled.
 *
 * <p>Every division truncates toward zero, and the products wrap as 32-bit values.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line. Held by the reference battles card_Tornado and"
            + " spell_tornado_into_push: the radial share by the configured speed, the direction,"
            + " the count and the two push bits. Translated but held by no run: the perpendicular"
            + " share, the mass factor, the coincident centre and the skipped jumping and dashing"
            + " states.")
public final class BuffPush {

  private static final int PERCENT = 100;

  private static final int MASS_SCALE = 1000;

  private BuffPush() {
    // Utility class
  }

  /**
   * Adds one pull to the entity's push accumulators.
   *
   * @param component the entity's movement component, whose push accumulators this writes
   * @param dx the centre of the pull less the entity's position, along the width
   * @param dy the centre of the pull less the entity's position, along the length
   * @param attractPercentage the buff's AttractPercentage
   * @param lateralPercentage the buff's LateralPushPercentage
   * @param massFactor the buff's PushMassFactor
   * @param speedFactor the buff's PushSpeedFactor
   * @param state the entity's state
   * @param speed the entity's configured speed
   * @param jumpHeight the entity's JumpHeight
   * @param side the entity's side; one of an even side is pulled from the centre itself toward y 0,
   *     one of an odd side away from it
   * @param mass the entity's mass, read only under a positive mass factor
   * @param air true for a flying entity
   * @param hovering true for a hovering entity
   */
  public static void push(
      MovementState component,
      int dx,
      int dy,
      int attractPercentage,
      int lateralPercentage,
      int massFactor,
      int speedFactor,
      int state,
      int speed,
      int jumpHeight,
      int side,
      int mass,
      boolean air,
      boolean hovering) {
    int lengthSquared = dx * dx + dy * dy;
    if (lengthSquared == 0) {
      // On the centre itself: straight toward the entity's own back line.
      dy = (side & 1) == 0 ? -1 : 1;
      lengthSquared = 1;
    }
    int length = Math.max(FixedMath.isqrt(lengthSquared), 1);
    if (state == GridEntityState.JUMPING) {
      return;
    }
    // The reference's state 3 with a jump height: a dash of a row that jumps.
    if (state == GridEntityState.DASHING && jumpHeight > 0) {
      return;
    }
    int attract = attractPercentage;
    int lateral = lateralPercentage;
    if (speedFactor >= 1) {
      attract = speedFactor * attractPercentage * speed / PERCENT;
      lateral = speedFactor * lateralPercentage * speed / PERCENT;
    }
    if (massFactor >= 1) {
      int weight = Math.min(mass * massFactor, MASS_SCALE);
      attract = attract + weight * attract / -MASS_SCALE;
      lateral = lateral + weight * lateral / -MASS_SCALE;
    }
    int radial = attract / PERCENT;
    int sideways = lateral / PERCENT;
    int pushX = radial * dx - sideways * dy;
    int pushY = radial * dy + sideways * dx;
    component.setPushX(component.getPushX() + pushX / length);
    component.setPushY(component.getPushY() + pushY / length);
    component.setPushStuck(air || hovering ? 0 : 1);
    component.setPushUnclamped(1);
    component.setPushCount(component.getPushCount() + 1);
  }
}
