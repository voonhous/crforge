package org.crforge.core.pathfinding.combat;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Which rule scales a stat by level.
 *
 * <p>Card stats are scaled by the rarity's multiplier table. Crown tower stats are compounded by a
 * percentage per level instead, with the king tower's hit points on their own percentage. The
 * published numbering is 1 to 6 in the order below; any other mode leaves the stat unscaled.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the six modes and which two read the rarity table. Not carried yet: the column"
            + " by which a projectile row asks for a tower mode instead of the card one.")
public enum ScalingMode {
  /** No scaling: the base is returned. */
  NONE,
  /** Damage of an ordinary character, projectile or building. */
  CARD_DAMAGE,
  /** Hit points of an ordinary character or building. */
  CARD_HITPOINTS,
  /** Damage of the king tower. */
  KING_DAMAGE,
  /** Hit points of the king tower. */
  KING_HITPOINTS,
  /** Damage of a princess tower. */
  TOWER_DAMAGE,
  /** Hit points of a princess tower. */
  TOWER_HITPOINTS;

  /** True for the two modes that read the rarity's multiplier table. */
  public boolean card() {
    return this == CARD_DAMAGE || this == CARD_HITPOINTS;
  }
}
