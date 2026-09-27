package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that swaps its owner's character row for another. It acts on the owner, reads nothing
 * else and schedules nothing: the owner keeps its hit points, level, state, tags and actions, and
 * the new row's starting action does not run. See the owner's swap for what changes. It does not
 * last.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by golemite_convert: the swap on the owner, the target read before it"
            + " and kept through the validator and the setter unless the row resets it. Not"
            + " modelled: a projectile row's swap, which a row asks for with a column that is"
            + " refused.")
public final class ChangeGameObjectData extends RowAction {

  private final String newCharacterData;
  private final boolean resetTarget;

  /**
   * @param row the row's shared columns
   * @param newCharacterData the name of the character row the owner takes
   * @param resetTarget true to give up the owner's target rather than keep it
   */
  public ChangeGameObjectData(ActionRow row, String newCharacterData, boolean resetTarget) {
    super(row);
    this.newCharacterData = newCharacterData;
    this.resetTarget = resetTarget;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    holder.getOwner().changeData(newCharacterData, resetTarget);
    return null;
  }
}
