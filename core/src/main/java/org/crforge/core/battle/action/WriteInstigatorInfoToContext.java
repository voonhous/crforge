/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that writes what its cause is like into the context it carries, as the evolved Pekka
 * notes the hit points of what it killed before the soul flies: under the row's hit points key, the
 * cause's hit points, and under its shield key, its shield hit points, both at the row's level -
 * its row's values that many steps above the Common first level. A crown tower it kills is read the
 * same way, by its own row. It writes into the main board, or the scratch board when the row says
 * so; a key the row leaves out is not written, and with no context, or no cause, nothing is.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the board chosen by UseScratch, a key the row leaves out not written, nothing"
            + " written without a context, and a character's hit points and shield at the row's"
            + " level, read as target_max_hp with an argument reads them; held by"
            + " pekka-resurrect-v2. A crown tower read the same way by its row, its hit points by"
            + " the princess tower's rule: held by a recorded battle where an evolved Pekka takes a"
            + " princess tower (the most heal). Not modelled: the cause's global id and position,"
            + " refused by the row builder; the level left out (-1), and a cause that is neither a character nor a"
            + " tower, which read its maximum and its shield as they stand, refused by the owner.")
public final class WriteInstigatorInfoToContext extends RowAction {

  /** The key that stands for none: a key the row leaves out. */
  public static final int NO_KEY = 0;

  private final boolean toScratch;
  private final int hitpointsKey;
  private final int shieldKey;
  private final int levelIndex;

  /**
   * @param row the row's shared columns
   * @param toScratch true to write into the scratch board, false for the main board
   * @param hitpointsKey the key of the cause's hit points, or {@link #NO_KEY}
   * @param shieldKey the key of the cause's shield hit points, or {@link #NO_KEY}
   * @param levelIndex the level the values are read at, in steps above the Common first level
   */
  public WriteInstigatorInfoToContext(
      ActionRow row, boolean toScratch, int hitpointsKey, int shieldKey, int levelIndex) {
    super(row);
    this.toScratch = toScratch;
    this.hitpointsKey = hitpointsKey;
    this.shieldKey = shieldKey;
    this.levelIndex = levelIndex;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    if (context == null) {
      return null;
    }
    if (hitpointsKey == NO_KEY && shieldKey == NO_KEY) {
      return null;
    }
    if (instigator == null || instigator.getOwner() == null) {
      return null;
    }
    int[] values = instigator.getOwner().contextHitpoints(levelIndex);
    if (hitpointsKey != NO_KEY) {
      context.write(toScratch, hitpointsKey, values[0]);
    }
    if (shieldKey != NO_KEY) {
      context.write(toScratch, shieldKey, values[1]);
    }
    return null;
  }
}
