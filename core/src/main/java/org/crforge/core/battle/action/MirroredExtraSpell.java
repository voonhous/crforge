/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A mirrored extra spell: the projectile a spell's cast throws besides its own, turned over across
 * the arena's width. The evolved Goblin Barrel's cast runs one on its side's king tower, with the
 * barrel it threw as the cause. The perform reads the cause, not the entity it runs on: from where
 * the cause stands, at its height, to the cause's aim turned over across the width, for the cause's
 * side and at its level. It does not last.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by evo_goblinbarrel_on_musketeer: a cast projectile as the cause, the"
            + " start, aim, side, level and creation order of the extra projectile. Refused: any"
            + " cause other than a projectile.")
public final class MirroredExtraSpell extends RowAction {

  /** The projectile row the extra spell throws. */
  @Getter private final String projectile;

  /**
   * @param row the row's shared columns
   * @param projectile the projectile row it throws
   */
  public MirroredExtraSpell(ActionRow row, String projectile) {
    super(row);
    this.projectile = projectile;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    if (cause == null) {
      throw new UnsupportedOperationException(
          name() + " runs with no cause to mirror, which is not modelled");
    }
    cause.mirroredExtraSpell(this);
    return null;
  }
}
