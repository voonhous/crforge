package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BlowdartDartSelect;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The run of the evolved Dart Goblin's dart choice on its unit. It does nothing on its steps; the
 * unit hands it each hit's projectile. See {@link BlowdartDartSelect} for the rules.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line from the class's projectile slot; held by"
            + " evo_blowdartgoblin_vs_musketeer. Refused: a hit with no target.")
public final class BlowdartDartSelectRun extends ActionInstance {

  private final BlowdartDartSelect select;
  private final WorldEntity unit;

  /** The holder of what caused the run, the unit itself for its starting action. */
  private final ActionHolder cause;

  BlowdartDartSelectRun(BlowdartDartSelect select, WorldEntity unit, ActionHolder cause) {
    super(select);
    this.select = select;
    this.unit = unit;
    this.cause = cause;
  }

  @Override
  protected void update(ActionHolder holder) {
    // The base step: nothing.
  }

  /**
   * Picks the projectile of one hit.
   *
   * @param handed the projectile the hit would launch
   * @param target the unit's target
   * @return the projectile the hit launches
   */
  ProjectileData select(ProjectileData handed, WorldEntity target) {
    if (target == null) {
      throw new UnsupportedOperationException(
          select.name() + " picks a dart for a hit with no target, not modelled");
    }
    ActionHolder targetHolder = target.actionHolder();
    BlowdartControllerRun controller = null;
    for (ActionInstance run : targetHolder.running()) {
      if (run instanceof BlowdartControllerRun found
          && found.getAction().name().equals(select.getController().name())
          && found.sameRun(cause)) {
        controller = found;
        break;
      }
    }
    ProjectileData special = unit.world.getRecords().projectile(select.getSpecialProjectile());
    if (controller == null) {
      // No controller of this thrower on the target: schedule one, the unit as its cause, and
      // throw the special dart.
      targetHolder.schedule(
          select.getController(), ActionHolder.OWN_DELAY, false, unit.actionHolder());
      return special;
    }
    ProjectileData picked = controller.nextDartSpecial() ? special : handed;
    controller.countDart();
    return picked;
  }
}
