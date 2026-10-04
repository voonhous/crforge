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
 * An action that launches a projectile: a spawn row of the projectile type, as the evolved
 * Furnace's quick spawn launches its spirit and the evolved Wall Breaker's killed action its
 * barrel. When it starts, one projectile of its row is launched from the point of the holder's
 * owner, with the owner as its launcher and owner, for the owner's side and at its level re-based
 * on the projectile's rarity, at no target, toward the point the two target expressions give, each
 * evaluated on the owner as the action starts and the owner's own coordinate for one that is not
 * set. It does not last.
 *
 * <p>The two classes differ in the height. A row of the location class starts it at the row's start
 * height, a row of the plain class at the row's start height above the owner's live height. A row
 * of either class aimed by neither expression launches it at the owner's current target, still
 * aimed at the owner's point; a dying unit's combat gate has switched its targeting off before its
 * killed action runs, so the evolved Wall Breaker's barrel has none, and the hero Musketeer's
 * turret holds none as its start launches its knockback.
 *
 * <p>A row without ParentGOAsSource takes its cause as the source in place of the owner. A
 * character's start schedules its starting action with the character as its own cause, so the
 * source of the turret's knockback is the turret itself, the same object, side and level as the
 * owner.
 *
 * <p>Refused rather than guessed, as the row is built: one that sets any spawn column besides its
 * data, its type, the start height, the two expressions, the source switch and the action to run on
 * what it spawned, which this branch does not read. Refused as it starts: a row without
 * ParentGOAsSource whose cause is missing or is not the owner; an owner that is a clone, whose
 * answer the projectile would copy; a row aimed by neither expression on an owner that is not a
 * character or whose targeting component is on and holds a reference, which the projectile would be
 * launched at.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the owner's point, the start height, the aim from the two"
            + " expressions evaluated on the owner, no target, the owner as launcher and owner and"
            + " its level; held by building_evolutions_barbarians, where the evolved Furnace"
            + " launches its spirits behind it to either side. The plain class's owner height and"
            + " a target dropped by the gate are held by evo_wallbreakers_vs_musketeer, where the"
            + " evolved Wall Breaker, shot dead on its way, launches its barrel on its own point."
            + " The cause as the source when it is the owner, and the location class aimed by"
            + " neither expression at no target, are held by ability_hero_musketeer, where the"
            + " hero Musketeer's turret launches its knockback on its own point as it starts."
            + " Refused: a cause other than the owner, an aim at a held target of either class,"
            + " the count, the positions, the offsets and a clone as the owner.")
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
   * True for a row of the plain spawn class, which adds the owner's live height to the start height
   * and, aimed by neither expression, launches at the owner's current target.
   */
  @Getter private final boolean spawnClass;

  /** True to take the owner as the source; false to take the cause, which must be the owner. */
  @Getter private final boolean parentGoAsSource;

  /**
   * @param row the row's shared columns
   * @param projectile the projectile row's name
   * @param startHeight the height it is launched from
   * @param aimX the aim along the arena's width, or null for the owner's own coordinate
   * @param aimY the aim along the arena's length, or null for the owner's own coordinate
   * @param spawnClass true for a row of the plain spawn class, false for the location class
   * @param parentGoAsSource true to take the owner as the source in place of the cause
   */
  public SpawnProjectile(
      ActionRow row,
      String projectile,
      int startHeight,
      IntSupplier aimX,
      IntSupplier aimY,
      boolean spawnClass,
      boolean parentGoAsSource) {
    super(row);
    this.projectile = projectile;
    this.startHeight = startHeight;
    this.aimX = aimX;
    this.aimY = aimY;
    this.spawnClass = spawnClass;
    this.parentGoAsSource = parentGoAsSource;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (!(holder.getOwner() instanceof SpawnHost owner)) {
      throw new UnsupportedOperationException(name() + " runs on an object that cannot spawn");
    }
    // Without ParentGOAsSource the source is the cause. Its reference is made from the cause as
    // the owner's is made from the owner, so a cause that is the owner launches as the owner.
    if (!parentGoAsSource && (instigator == null || instigator.getOwner() != holder.getOwner())) {
      throw new UnsupportedOperationException(
          name() + " spawns a projectile from a cause other than its owner, which is not modelled");
    }
    owner.spawnProjectile(
        name(), projectile, startHeight, aimX, aimY, spawnClass, holder.passPhase());
    return null;
  }
}
