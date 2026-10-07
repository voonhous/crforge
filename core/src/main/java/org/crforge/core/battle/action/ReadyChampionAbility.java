package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that changes the champion slot following the character it runs on: the Mega Minion
 * hero's mark runs it on the hero as its marked object leaves. It has no run: it does what it does
 * as it starts.
 *
 * <p>It first stores AbilityCanResetAfter on the character, which only travels with the character's
 * state and changes nothing. Then, when the character is a champion that is not a clone and a slot
 * of its side's king follows it: ReadyAbility clears the slot's cooldown, or else ForceCooldown
 * restarts it at the slot's full cooldown; RecoverCharge then gives a charge back, up to the
 * champion's most. With no slot following the character it does nothing more.
 *
 * <p>Only the forced cooldown is modelled: the one row the data has sets ReadyAbility off and
 * RecoverCharge off, and a row that sets either on is refused as it is built. The charges and the
 * button state are left as they are; the slot works its state out again on its own next step.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the slot that follows the character, nothing without one, and ForceCooldown"
            + " restarting its cooldown at the full cooldown with the charges kept; held by"
            + " BattleMegaMinionMarkDiedTest. AbilityCanResetAfter is stored on the character"
            + " and read by nothing. Refused as the row is built: ReadyAbility and RecoverCharge,"
            + " which no row sets on.")
public final class ReadyChampionAbility extends RowAction {

  /** True when it restarts the slot's cooldown at the full cooldown. */
  @Getter private final boolean forceCooldown;

  /**
   * @param row the row's shared columns
   * @param forceCooldown true to restart the slot's cooldown at the full cooldown
   */
  public ReadyChampionAbility(ActionRow row, boolean forceCooldown) {
    super(row);
    this.forceCooldown = forceCooldown;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    holder.getOwner().readyChampionAbility(this);
    return null;
  }
}
