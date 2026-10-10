/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.CannonBarrage;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One run of a barrage on a character: its first update makes every bomb's area effect, in the
 * row's order, and finishes; the next run pass removes it.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: every bomb in the first update, x from the absolute offset, y"
            + " from the owner's y and the vertical offset negated for side 1, and the finish;"
            + " held by the reference battle evo_cannon_vs_giant. Refused: a bomb off the arena,"
            + " which ends the barrage, and a mode of four players, whose team rule is not"
            + " modelled.")
final class CannonBarrageRun extends ActionInstance {

  /** Game units per offset tile. */
  private static final int TILE = 500;

  private final CannonBarrage row;
  private final CharacterEntity unit;

  /**
   * @param row the barrage
   * @param unit the character it runs on
   * @param phase the phase of the pending pass that ran it
   */
  CannonBarrageRun(CannonBarrage row, CharacterEntity unit, int phase) {
    super(row);
    this.row = row;
    this.unit = unit;
    unit.world().barrageStarted(unit, row.name(), phase);
  }

  @Override
  protected void update(ActionHolder holder) {
    List<AreaEffectEntity> made = new ArrayList<>();
    for (int i = 0; i < row.getAreaEffects().size(); i++) {
      int x = row.getAbsoluteHorizontalOffsets().get(i) * TILE;
      int v = row.getVerticalOffsets().get(i) * TILE;
      // Forward for either side: along the length for side 0, against it for side 1.
      if (unit.side() != 0) {
        v = -v;
      }
      int y = v + unit.getView().getY();
      if ((x | y) < 0) {
        throw new UnsupportedOperationException(
            row.name() + " places a bomb off the arena, which ends the barrage, not modelled");
      }
      made.add(unit.world().barrageAreaEffect(unit, row.getAreaEffects().get(i), x, y));
    }
    finish();
    unit.world().barrageStepped(unit, row.name(), made);
  }
}
