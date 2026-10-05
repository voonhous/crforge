package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that gives the projectile it runs on a speed in place of its row's, as the Balloon
 * hero's skeleton trooper speeds up while it falls: SpeedOverride is evaluated with the context the
 * action runs with, 0 for a row without one, and written into the projectile's override, which its
 * flight takes in place of the row's speed while it is above 0. It does not last. An owner other
 * than a projectile is refused: the field written is a projectile's.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the expression evaluated on the owner with the context, its"
            + " default 0, the value written as the override the flight reads when it is above 0;"
            + " held by hero_balloon and BattleBalloonHeroTest.")
public final class OverrideProjectileSpeed extends RowAction {

  /** The speed, or null for a row without one. */
  private final IntSupplier speed;

  /**
   * @param row the row's shared columns
   * @param speed the speed expression, or null for none
   */
  public OverrideProjectileSpeed(ActionRow row, IntSupplier speed) {
    super(row);
    this.speed = speed;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ActionOwner owner = holder.getOwner();
    if (owner == null) {
      return null;
    }
    owner.overrideProjectileSpeed(name(), speed == null ? 0 : speed.getAsInt());
    return null;
  }
}
