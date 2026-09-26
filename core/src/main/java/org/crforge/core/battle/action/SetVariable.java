package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that writes a number a later expression reads: when it starts it evaluates its value
 * and writes it to its owner's variable, the same one an expression naming that variable reads. A
 * row without a variable does nothing, and does not evaluate its value.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled and held by the recorded cases: the value evaluated and written to the owner's"
            + " variable, replaced by a second write, a second key a second entry, a key never"
            + " written read as zero, and nothing evaluated without a variable.")
public final class SetVariable extends RowAction {

  /** The key of a row without a variable. */
  public static final int NO_VARIABLE = -1;

  private final IntSupplier value;
  private final int key;

  /**
   * @param row the row's shared columns
   * @param value the value's expression
   * @param key the variable's key, or {@link #NO_VARIABLE}
   */
  public SetVariable(ActionRow row, IntSupplier value, int key) {
    super(row);
    this.value = value;
    this.key = key;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ActionOwner owner = holder.getOwner();
    if (key == NO_VARIABLE || owner == null) {
      return null;
    }
    owner.setVariable(key, value.getAsInt());
    return null;
  }
}
