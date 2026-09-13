package org.crforge.core.card;

import java.util.List;
import org.crforge.core.util.FormationHelper;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.GameUnits;

/**
 * Formation data of a multi-unit troop card, resolving each unit's blue-side deploy offset in game
 * units. Callers mirror the offset for the red team (negate both axes).
 *
 * @param layout layout selection
 * @param formationOffsets explicit offsets in game units, or null
 * @param summonRadius raw summonRadius data value (0 when absent)
 * @param primaryCount number of primary units
 * @param secondaryCount number of secondary units
 */
public record DeployFormation(
    FormationLayoutType layout,
    List<int[]> formationOffsets,
    float summonRadius,
    int primaryCount,
    int secondaryCount) {

  /** Formation of a single unit with no layout data (always spawns at the deploy point). */
  public static final DeployFormation SINGLE =
      new DeployFormation(FormationLayoutType.DEFAULT, null, 0f, 1, 0);

  /** Builds the formation description of a card. */
  public static DeployFormation of(Card card) {
    return new DeployFormation(
        card.getFormationLayout(),
        card.getFormationOffsets(),
        card.getSummonRadius(),
        card.getUnitCount(),
        card.getSecondaryUnitCount());
  }

  /** Total number of units placed by this formation. */
  public int totalCount() {
    return primaryCount + secondaryCount;
  }

  /**
   * Returns the blue-side offset for the unit at {@code index}.
   *
   * @param index zero-based unit index (primary units first)
   * @param unitCollisionRadius collision radius of the unit being placed, in game units
   */
  public FormationLayout.Offset offsetFor(int index, int unitCollisionRadius) {
    int total = totalCount();
    if (layout == FormationLayoutType.RADIAL) {
      if (total <= 1) {
        return FormationLayout.Offset.ZERO;
      }
      // Radius selection: a nonzero card summonRadius overrides the unit's collision radius. Raw
      // summonRadius values are already game units. A unit-level spawn radius override also exists
      // in the source data but is not exported, so it is not applied.
      int radius = summonRadius > 0 ? GameUnits.round(summonRadius) : unitCollisionRadius;
      return FormationHelper.offset(index, primaryCount, secondaryCount, radius, 0, 0);
    }
    if (formationOffsets != null && index < formationOffsets.size()) {
      // Pre-computed offsets (already converted to game units once, at card load time)
      int[] offset = formationOffsets.get(index);
      return new FormationLayout.Offset(offset[0], offset[1]);
    }
    if (total > 1 && summonRadius > 0) {
      // Legacy circular formation algorithm (raw summonRadius / 355 tiles)
      return FormationLayout.calculateDeployOffset(index, total, summonRadius, unitCollisionRadius);
    }
    return FormationLayout.Offset.ZERO;
  }
}
