/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that writes a value into the context it carries: its Value expression, evaluated as it
 * starts with that context, under its key, into the main board or, with UseScratch, the scratch
 * board. With no context it does nothing, its expression not evaluated.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: nothing without a context, the value evaluated with the context, the board"
            + " chosen by UseScratch and the write under the key; held by ActionContextTest.")
public final class BlackboardSetInt extends RowAction {

  private final boolean toScratch;
  private final int key;
  private final IntSupplier value;

  /**
   * @param row the row's shared columns
   * @param toScratch true to write into the scratch board, false for the main board
   * @param key the key
   * @param value the value's expression, or null for 0
   */
  public BlackboardSetInt(ActionRow row, boolean toScratch, int key, IntSupplier value) {
    super(row);
    this.toScratch = toScratch;
    this.key = key;
    this.value = value;
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
    context.write(toScratch, key, value == null ? 0 : value.getAsInt());
    return null;
  }
}
