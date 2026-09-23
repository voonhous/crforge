package org.crforge.core.pathfinding.combat;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The published scaling row of one rarity: where its levels start, how many there are, and the
 * multiplier table a card stat is scaled by.
 *
 * <p>Levels are counted from 1 for every rarity in a match, but a rarity's cards only exist from
 * its first level on, and the scaling counts steps above that first level. The relative level is
 * the number of levels below the first one: 0 for Common, 2 for Rare, 5 for Epic, 8 for Legendary
 * and Experimental, 10 for Champion. A card at level 11 is therefore 10 steps up for a Common card
 * and 8 steps up for a Rare one, and every rarity's last level is 16.
 *
 * @param name the published rarity name
 * @param relativeLevel levels below the rarity's first level
 * @param levelCount how many levels the rarity's cards have
 * @param powerLevelMultiplier the multiplier, in hundredths, for each step above the first level;
 *     entry 0 is the second level's
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the six published rows, and a multiplier past the table compounding a tenth"
            + " per step, truncated. Not settled: the bound the lookup compares against is taken"
            + " as the published table's length. A negative index, which the scaling never"
            + " produces, is refused here rather than read from before the table.")
public record RarityTable(
    String name, int relativeLevel, int levelCount, List<Integer> powerLevelMultiplier) {

  public static final RarityTable COMMON =
      new RarityTable(
          "Common",
          0,
          16,
          List.of(
              110, 121, 133, 146, 160, 176, 193, 212, 233, 256, 281, 309, 339, 372, 409, 450, 495,
              545, 600));

  public static final RarityTable RARE =
      new RarityTable(
          "Rare",
          2,
          14,
          List.of(
              110, 121, 133, 146, 160, 176, 193, 212, 233, 256, 281, 309, 339, 372, 409, 450, 495));

  public static final RarityTable EPIC =
      new RarityTable(
          "Epic",
          5,
          11,
          List.of(110, 121, 133, 146, 160, 176, 193, 212, 233, 256, 281, 309, 339, 372));

  public static final RarityTable LEGENDARY =
      new RarityTable(
          "Legendary", 8, 8, List.of(110, 121, 133, 146, 160, 176, 193, 212, 233, 256, 281));

  public static final RarityTable CHAMPION =
      new RarityTable("Champion", 10, 6, List.of(110, 121, 133, 146, 160, 176, 193, 212, 233));

  public static final RarityTable EXPERIMENTAL =
      new RarityTable("Experimental", 8, 8, List.of(110, 121, 133, 146, 160, 176, 193, 212, 233));

  /** The six published rows. */
  public static final List<RarityTable> PUBLISHED =
      List.of(COMMON, RARE, EPIC, LEGENDARY, CHAMPION, EXPERIMENTAL);

  public RarityTable {
    powerLevelMultiplier = List.copyOf(powerLevelMultiplier);
  }

  /** The rarity's first level, counted from 1. */
  public int firstLevel() {
    return relativeLevel + 1;
  }

  /**
   * The multiplier for one step index, in hundredths. Within the table it is the table's entry.
   * Past it, 100 is compounded by a tenth {@code index + 1} times, truncated at every step, which
   * continues the published tables' own rule.
   *
   * @param index steps above the first level, less one
   */
  public int multiplier(int index) {
    checkArgument(index >= 0, () -> "multiplier index must not be negative, got " + index);
    if (powerLevelMultiplier.size() > index) {
      return powerLevelMultiplier.get(index);
    }
    int multiplier = 100;
    for (int i = 0; i <= index; i++) {
      multiplier += multiplier / 10;
    }
    return multiplier;
  }
}
