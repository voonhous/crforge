package org.crforge.core.pathfinding.combat;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A level as an entity carries it: the rarity's relative level in the high bits and, in the low
 * byte, the steps above the rarity's first level.
 *
 * <p>A level counted from 1 across all rarities is the packed form of a Common card, so a level is
 * packed by re-basing that value on the entity's own rarity: the steps become the level index less
 * the rarity's relative level, floored at zero, so a card below its rarity's first level is scaled
 * as if it stood on it. A value already based on the rarity is returned unchanged.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the packing, its re-basing on the rarity and the floor at zero steps. Not"
            + " settled: nothing here; the class only exists so that the level is packed once,"
            + " at creation, as the entity does.")
public final class PackedLevel {

  private PackedLevel() {
    // Utility class
  }

  /**
   * Re-bases a packed level on a rarity.
   *
   * @param level a packed level, or a bare level index for a level counted from 1
   * @param rarity the rarity to base the level on
   */
  public static int pack(int level, RarityTable rarity) {
    int high = level >> 8;
    int relative = rarity.relativeLevel();
    if (high == relative) {
      return level;
    }
    int steps = Math.max(high + (byte) level - relative, 0);
    return (relative << 8) | (steps & 0xff);
  }

  /**
   * Packs a level counted from 1, as the card library and the match count it, for an entity of the
   * given rarity.
   */
  public static int fromLevel(int level, RarityTable rarity) {
    checkArgument(level >= 1, () -> "a level is counted from 1, got " + level);
    return pack(level - 1, rarity);
  }

  /** Steps above the rarity's first level: the packed level's low byte, sign extended. */
  public static int steps(int packed) {
    return (byte) packed;
  }

  /** The rarity's relative level the packed level is based on. */
  public static int relativeLevel(int packed) {
    return packed >> 8;
  }

  /** The level counted from 1, as the card library and the match count it. */
  public static int level(int packed) {
    return relativeLevel(packed) + steps(packed) + 1;
  }
}
