package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Makes a row of parallel projectiles across the line of the projectile it runs on, as the hero
 * Elite Archer's ability shot starts its two side shots as it sets off:
 * ActionCreateParallelProjectiles in data version 16.402.18,
 * ActionShootProjectilesInCharacterDirection in 14.593.1, whose perform does the same for a
 * projectile. The perform reads the entity the action runs on, not the cause: for a projectile, its
 * line runs from where it stands to the point it flies to (where its target stands when it homes
 * onto one). It makes ProjectileCount projectiles of its row, spread ProjectileDistance across: the
 * first half the distance to the right of that line, each next one the distance divided by one less
 * than the count (at least 2) further along. Each starts at the entity's point moved by its offset
 * across the line and is shot at that start plus the line set to the row's ProjectileRange (or the
 * line's own length without one). It does not last.
 *
 * <p>The two classes differ only for another owner: 16.402.18's makes nothing for an entity other
 * than a projectile, while 14.593.1's also shoots from a character, its line starting its
 * projectile start radius (or CustomForwardOffset, a column 16.402.18's class does not have) along
 * it. No row runs either on anything but a projectile.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by ability_hero_elite_archer on both data versions: the starts, aims,"
            + " side, level and creation order of the side shots of a projectile that flies to its"
            + " aim. Refused: an entity other than a projectile (16.402.18 makes nothing for one,"
            + " 14.593.1 shoots from a character; no row reaches either), a ShooterData row.")
public final class CreateParallelProjectiles extends RowAction {

  /** The projectile row it shoots. */
  @Getter private final String projectile;

  /** How many it shoots. */
  @Getter private final int count;

  /** How far apart the outermost two are, across the line. */
  @Getter private final int distance;

  /**
   * @param row the row's shared columns
   * @param projectile the projectile row it shoots
   * @param count how many it shoots
   * @param distance how far apart the outermost two are, across the line
   */
  public CreateParallelProjectiles(ActionRow row, String projectile, int count, int distance) {
    super(row);
    this.projectile = projectile;
    this.count = count;
    this.distance = distance;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    // No projectile row or no count shoots nothing.
    if (projectile != null && count >= 1) {
      holder.getOwner().shootProjectilesAcross(this);
    }
    return null;
  }
}
