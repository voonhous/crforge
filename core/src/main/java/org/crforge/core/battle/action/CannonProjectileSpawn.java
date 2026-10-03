package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A bomb of the evolved Cannon's barrage, dropped onto the area effect that caused it: its
 * projectile is launched from that area effect's point at the row's height, with the area effect as
 * its launcher, at its level re-based on the projectile's rarity, aimed straight down at the same
 * point, at a speed that lands it over the area effect's lifetime - the height over the lifetime's
 * steps of 50 ms. It does not last.
 *
 * <p>Refused as the row is built: a projectile that sets a column not modelled or homes, and the
 * shared columns but its next action. Refused as it starts: a cause other than a live area effect.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the cause's point and side, the height, the aim at the same point,"
            + " the speed from the height and the cause's lifetime, and the cause as the"
            + " launcher; held by building_evolutions_barbarians. Refused: a homing projectile, a"
            + " cause other than an area effect, and the shared columns but the next action.")
public final class CannonProjectileSpawn extends RowAction {

  /** The projectile row's name. */
  @Getter private final String projectile;

  /** The height it is dropped from. */
  @Getter private final int height;

  /**
   * @param row the row's shared columns
   * @param projectile the projectile row's name
   * @param height the height it is dropped from
   */
  public CannonProjectileSpawn(ActionRow row, String projectile, int height) {
    super(row);
    this.projectile = projectile;
    this.height = height;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator == null) {
      throw new UnsupportedOperationException(name() + " runs without a cause, not modelled");
    }
    instigator.getOwner().cannonBomb(this, holder.passPhase());
    return null;
  }
}
