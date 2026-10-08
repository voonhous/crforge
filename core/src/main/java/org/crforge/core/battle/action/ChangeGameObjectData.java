package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that swaps its owner's character row, or its projectile row, for another. It acts on
 * the owner, reads nothing else and schedules nothing: the owner keeps its hit points, level,
 * state, tags and actions, and the new row's starting action does not run. See the owner's swap for
 * what changes. It does not last.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by BattleChangeDataTest: the swap on the owner, the target read before"
            + " it and kept through the validator and the setter unless the row resets it. Held"
            + " by the reference battle card_GoblinDemolisher and BattleChangeDataTest: a reset"
            + " target and a walking row whose lifetime drains. A projectile row's swap on the"
            + " evolved Executioner's axe, held by evo_axeman_vs_musketeer.")
public final class ChangeGameObjectData extends RowAction {

  private final String newCharacterData;
  private final boolean resetTarget;

  /** The projectile row the owner takes, or null for a character row's swap. */
  private final String newProjectileData;

  /**
   * @param row the row's shared columns
   * @param newCharacterData the name of the character row the owner takes
   * @param resetTarget true to give up the owner's target rather than keep it
   */
  public ChangeGameObjectData(ActionRow row, String newCharacterData, boolean resetTarget) {
    this(row, newCharacterData, resetTarget, null);
  }

  /**
   * @param row the row's shared columns
   * @param newCharacterData the name of the character row the owner takes, or null for a projectile
   *     row's swap
   * @param resetTarget true to give up the owner's target rather than keep it
   * @param newProjectileData the name of the projectile row the owner takes, or null
   */
  public ChangeGameObjectData(
      ActionRow row, String newCharacterData, boolean resetTarget, String newProjectileData) {
    super(row);
    this.newCharacterData = newCharacterData;
    this.resetTarget = resetTarget;
    this.newProjectileData = newProjectileData;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    if (newProjectileData != null) {
      holder.getOwner().changeProjectileData(newProjectileData);
    } else {
      holder.getOwner().changeData(newCharacterData, resetTarget);
    }
    return null;
  }
}
