package org.crforge.core.battle.spawn;

import java.util.function.IntSupplier;
import lombok.Getter;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that launches a projectile: a spawn row of the location class and the projectile type,
 * as the evolved Furnace's quick spawn launches its spirit. When it starts, one projectile of its
 * row is launched from the point of the holder's owner at the row's start height, with the owner as
 * its launcher and owner, for the owner's side and at its level re-based on the projectile's
 * rarity, at no target, toward the point the two target expressions give, each evaluated on the
 * owner as the action starts and the owner's own coordinate for one that is not set. It does not
 * last.
 *
 * <p>Refused rather than guessed, as the row is built: the spawn class, a row whose source is the
 * cause rather than the owner, one with neither target expression, which would aim at the owner's
 * target, and one that sets any spawn column besides its data, its type, the start height, the two
 * expressions and the action to run on what it spawned, which this branch does not read. Refused as
 * it starts: an owner that is a clone, whose answer the projectile would copy.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the owner's point, the start height, the aim from the two"
            + " expressions evaluated on the owner, no target, the owner as launcher and owner and"
            + " its level; held by building_evolutions_barbarians, where the evolved Furnace"
            + " launches its spirits behind it to either side. Refused: the spawn class, the cause"
            + " as the source, the aim at the owner's target, the count, the positions, the offsets"
            + " and a clone as the owner.")
public final class SpawnProjectile extends RowAction {

  /** The projectile row's name. */
  @Getter private final String projectile;

  /** The height it is launched from. */
  @Getter private final int startHeight;

  /** The aim along the arena's width, or null for the owner's own coordinate. */
  private final IntSupplier aimX;

  /** The aim along the arena's length, or null for the owner's own coordinate. */
  private final IntSupplier aimY;

  /**
   * @param row the row's shared columns
   * @param projectile the projectile row's name
   * @param startHeight the height it is launched from
   * @param aimX the aim along the arena's width, or null for the owner's own coordinate
   * @param aimY the aim along the arena's length, or null for the owner's own coordinate
   */
  public SpawnProjectile(
      ActionRow row, String projectile, int startHeight, IntSupplier aimX, IntSupplier aimY) {
    super(row);
    this.projectile = projectile;
    this.startHeight = startHeight;
    this.aimX = aimX;
    this.aimY = aimY;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    if (!(holder.getOwner() instanceof SpawnHost owner)) {
      throw new UnsupportedOperationException(name() + " runs on an object that cannot spawn");
    }
    owner.spawnProjectile(name(), projectile, startHeight, aimX, aimY, holder.passPhase());
    return null;
  }
}
