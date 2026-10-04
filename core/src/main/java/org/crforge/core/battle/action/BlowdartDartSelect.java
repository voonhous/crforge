package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Dart Goblin's dart choice, its starting action: a run that lasts and does nothing on
 * its steps, but picks the projectile each of the unit's hits launches.
 *
 * <p>On each hit that launches projectiles, the unit's projectile is handed to the run, which looks
 * on the unit's current target for a run of its controller row (ActionToTakeDataFrom) that this
 * unit caused. With none, it schedules the controller row on the target, the unit as its cause, and
 * the hit launches SpecialProjectile. With one, the hit launches SpecialProjectile when the
 * controller has not reached its last stack and its darts, this one counted, reach the next stack's
 * count; otherwise the unit's own projectile. Either way the dart is then counted on the
 * controller, which may step its stack.
 *
 * <p>Refused: a hit with no target, and a unit with a custom first projectile, whose two
 * projectiles would each be handed to the run.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the hand-over of the hit's projectile to the run, the lookup of the controller"
            + " this unit caused on the target, the controller scheduled with the unit as its"
            + " cause and the special dart for a target without one, and the special dart when"
            + " the darts reach the next stack's count; held by evo_blowdartgoblin_vs_musketeer."
            + " Refused: a hit with no target and a unit with a custom first projectile.")
public final class BlowdartDartSelect extends RowAction {

  /** The controller row whose run on the target counts the darts. */
  @Getter private final BattleAction controller;

  /** The projectile a special dart is. */
  @Getter private final String specialProjectile;

  /**
   * @param row the row's shared columns
   * @param controller the controller row whose run on the target counts the darts
   * @param specialProjectile the projectile a special dart is
   */
  public BlowdartDartSelect(ActionRow row, BattleAction controller, String specialProjectile) {
    super(row);
    this.controller = controller;
    this.specialProjectile = specialProjectile;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return holder.getOwner().blowdartDartSelect(this, instigator);
  }
}
