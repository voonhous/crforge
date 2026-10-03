package org.crforge.core.battle.action;

import java.util.List;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Cannon's barrage: a run on its owner whose first update makes every bomb's area
 * effect at once and finishes. Bomb i stands at its absolute offset times 500 across the arena,
 * wherever the owner is, and at the owner's y plus its vertical offset times 500, forward for
 * either side; it is its own row's area effect for the owner's side and at its level, re-based on
 * the area effect's rarity, with no parent, handed to the holder, which admits it at the tick's
 * closing cleanup.
 *
 * <p>Refused as the row is built: a bomb without an absolute, non-negative offset, which the
 * relative offset would place, an area effect a bomb names that sets a column not modelled, and the
 * shared columns its run does not read. Refused as it runs: a bomb off the arena, which ends the
 * barrage, and an owner other than a character.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: every bomb in the first update, its point from the absolute offset"
            + " and the owner's y forward for its side, its row, side and level, no parent, and the"
            + " finish; held by building_evolutions_barbarians. Refused: the relative offset, the"
            + " team rule of a mode of four players, a bomb off the arena and the shared columns.")
public final class CannonBarrage extends RowAction {

  /** Each bomb's distance ahead of the owner, in tiles of 500. */
  @Getter private final List<Integer> verticalOffsets;

  /** Each bomb's point across the arena, in tiles of 500. */
  @Getter private final List<Integer> absoluteHorizontalOffsets;

  /** Each bomb's area effect row. */
  @Getter private final List<String> areaEffects;

  /**
   * @param row the row's shared columns
   * @param verticalOffsets each bomb's distance ahead of the owner, in tiles of 500
   * @param absoluteHorizontalOffsets each bomb's point across the arena, in tiles of 500
   * @param areaEffects each bomb's area effect row
   */
  public CannonBarrage(
      ActionRow row,
      List<Integer> verticalOffsets,
      List<Integer> absoluteHorizontalOffsets,
      List<String> areaEffects) {
    super(row);
    this.verticalOffsets = List.copyOf(verticalOffsets);
    this.absoluteHorizontalOffsets = List.copyOf(absoluteHorizontalOffsets);
    this.areaEffects = List.copyOf(areaEffects);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().cannonBarrage(this, holder.passPhase());
  }
}
