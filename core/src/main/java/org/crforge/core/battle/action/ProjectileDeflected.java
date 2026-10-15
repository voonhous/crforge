/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A projectile's own deflection, the action a row's CustomDeflectAction names, as the evolved
 * Snowball's rolling snowball has: a deflection runs it on the projectile in place of turning the
 * projectile around, the deflector's parent its cause (see the projectile's deflection). As it
 * starts it counts one more deflection on its owner, a projectile, and hands it the cause's side;
 * nothing else of the projectile changes, so it flies, or rolls, on as before, and whatever moves
 * it reads the count. It reads no column of its own and does not last.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: scheduled on the projectile by the deflection, the deflector's parent its cause;"
            + " the count of deflections raised by one and the cause's side handed to the"
            + " projectile, held by the reference battles of snowball-ev1-roll-deflect-v1."
            + " Refused: an owner other than a projectile, a cause other than a unit, and a"
            + " deflection past the most a projectile takes, which ends its flight and which no"
            + " recording holds.")
public final class ProjectileDeflected extends RowAction {

  /**
   * @param row the row's shared columns
   */
  public ProjectileDeflected(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    holder.getOwner().projectileDeflected(this, instigator == null ? null : instigator.getOwner());
    return null;
  }
}
