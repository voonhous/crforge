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
 * An action that writes into the champion slot of its owner's player that follows a named champion
 * row: a button state that wins that slot's next working out, and, when the row asks, the slot's
 * charges refilled to the champion's most. The owner only decides the player; the champion is the
 * one the row names, and with no slot of that player following it the action does nothing.
 *
 * <p>A persistent row (the default) keeps a run that writes the same again on every step until it
 * is stopped by its stop gate or its owner leaving; the slot clears the state at the end of each of
 * its own steps, so the state holds only while something writes it. A row that is not persistent
 * writes once.
 *
 * <p>On a projectile the row's perform asks the object that launched it instead, and writes nothing
 * when that is gone or is not a character or a building; a spell's projectile is launched by its
 * side's king. The persistent run's later writes go straight by the projectile's own side.
 *
 * <p>The state is the champion button's: what the player's client draws and the order its reasons
 * are tried in. The slot's activation, the ability command's gates and the cast never read it; the
 * charges are read by the command's gate on a use.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled: the slot of the owner's player that follows the named row, the state written"
            + " when the row gives one, the charges refilled to the most, the persistent run"
            + " writing both every step, and nothing without a following slot; held by"
            + " hero_goblins. A projectile's perform handing over to its launcher and its run"
            + " writing by its own side: held by hero_barb_log.")
public final class OverrideAbilityButtonState extends RowAction {

  /** The champion row whose slot it writes into. */
  @Getter private final String champion;

  /** The state it writes, or 0 for a row that gives none. */
  @Getter private final int state;

  /** True when it refills the slot's charges. */
  @Getter private final boolean resetCharges;

  /** True when it keeps a run that writes again on every step. */
  @Getter private final boolean persistent;

  /**
   * @param row the row's shared columns
   * @param champion the champion row whose slot it writes into
   * @param state the state it writes, or 0 for none
   * @param resetCharges true to refill the slot's charges
   * @param persistent true to keep a run that writes again on every step
   */
  public OverrideAbilityButtonState(
      ActionRow row, String champion, int state, boolean resetCharges, boolean persistent) {
    super(row);
    this.champion = champion;
    this.state = state;
    this.resetCharges = resetCharges;
    this.persistent = persistent;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    // The perform's write may hand the question to another object; the run's later writes do not.
    holder.getOwner().performAbilityButtonOverride(this);
    if (!persistent) {
      return null;
    }
    return new ActionInstance(this) {
      @Override
      protected void update(ActionHolder h) {
        write(h);
      }
    };
  }

  /** Writes the state and the charges into the slot that follows the champion, if any. */
  private void write(ActionHolder holder) {
    holder.getOwner().overrideAbilityButton(this);
  }
}
