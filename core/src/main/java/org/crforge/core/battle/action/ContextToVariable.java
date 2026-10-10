/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that copies a value of the context it carries into one of its owner's variables: the
 * value under its BlackboardKey in the main board or, with UseScratch, the scratch board alone,
 * else its DefaultValue. With no context, or no variable, it does nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: nothing without a context or a variable, the one board UseScratch picks, the"
            + " default for a key it does not hold, and the owner's variable written; held by"
            + " ActionContextTest.")
public final class ContextToVariable extends RowAction {

  private final boolean fromScratch;
  private final int key;
  private final int defaultValue;
  private final int variableKey;

  /**
   * @param row the row's shared columns
   * @param fromScratch true to read the scratch board, false for the main board
   * @param key the key read
   * @param defaultValue the value for a key the board does not hold
   * @param variableKey the owner's variable written, or {@link SetVariable#NO_VARIABLE}
   */
  public ContextToVariable(
      ActionRow row, boolean fromScratch, int key, int defaultValue, int variableKey) {
    super(row);
    this.fromScratch = fromScratch;
    this.key = key;
    this.defaultValue = defaultValue;
    this.variableKey = variableKey;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    if (context == null || variableKey == SetVariable.NO_VARIABLE || holder.getOwner() == null) {
      return null;
    }
    Integer value = context.readBoard(fromScratch, key);
    holder.getOwner().setVariable(variableKey, value != null ? value : defaultValue);
    return null;
  }
}
