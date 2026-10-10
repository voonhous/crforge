/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.SpawnResetableAreaEffect;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One run of a resetable area effect on a character: the id of the area effect it made, -1 once
 * that has left.
 *
 * <p>As it starts it makes the area effect at the character's point moved by the row's offsets, the
 * one along the length times the team's direction, so toward the enemy side whichever side the
 * character is on; the area effect, following the character, keeps the same offsets. The run ends
 * on the update after its area effect has left. A singleton row started again gives a live area
 * effect its whole lifetime back. As the character leaves, after every notice of it, a live area
 * effect keeps no more of its life than the row's stay.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start, the update's end, the re-trigger, the release and the"
            + " leave notice; held by the reference battle evo_babydragon_vs_musketeer. The"
            + " release as the run pass removes the finished run finds no area effect, which has"
            + " left by then.")
final class ResetableAreaEffectRun extends ActionInstance {

  /** The id of no area effect. */
  private static final int NONE = -1;

  private final SpawnResetableAreaEffect row;
  private final CharacterEntity unit;

  /** The id of the area effect it made, or {@link #NONE} once that has left. */
  private int areaEffectId;

  /** The area effect's name, for the observers. */
  private final String areaEffectName;

  /**
   * The start: the area effect made, following the character with the offsets.
   *
   * @param row the row
   * @param unit the character it runs on
   * @param phase the phase of the pending pass that ran it
   * @param instigator what caused it, or null
   */
  ResetableAreaEffectRun(
      SpawnResetableAreaEffect row, CharacterEntity unit, int phase, WorldEntity instigator) {
    super(row);
    this.row = row;
    this.unit = unit;
    int x = unit.getView().getX() + row.getOffsetX();
    int y = AreaEffectEntity.yDirection(unit.side()) * row.getOffsetY() + unit.getView().getY();
    AreaEffectEntity areaEffect = unit.world().resetableAreaEffect(unit, row.getAreaEffect(), x, y);
    areaEffect.followWithOffsets(row.getOffsetX(), row.getOffsetY());
    areaEffectId = areaEffect.getId();
    areaEffectName = areaEffect.name();
    unit.world().resetableStarted(unit, phase, instigator, areaEffect, x, y);
  }

  @Override
  protected void update(ActionHolder holder) {
    if (areaEffectId == NONE) {
      finish();
      unit.world().resetableEnded(unit, areaEffectName);
    }
  }

  /** A second start of the singleton row: a live area effect's whole lifetime back. */
  @Override
  protected void retrigger(ActionHolder holder) {
    Integer countdown = null;
    if (live() instanceof AreaEffectEntity areaEffect) {
      areaEffect.restartLife();
      countdown = areaEffect.getCountdown();
    }
    unit.world().resetableRetriggered(unit, holder.passPhase(), areaEffectName, countdown);
  }

  /** The area effect leaving is forgotten. */
  @Override
  protected void objectLeft(int leftId) {
    if (areaEffectId != NONE && leftId == areaEffectId) {
      areaEffectId = NONE;
      unit.world().resetableLeft(unit, areaEffectName);
    }
  }

  /** As the character leaves: a live area effect's life cut to the row's stay. */
  @Override
  protected void stop(ActionHolder holder) {
    int keep = row.getStayAliveAfterParentDiesMs();
    if (keep < 0 || !(live() instanceof AreaEffectEntity areaEffect)) {
      return;
    }
    int before = areaEffect.getCountdown();
    areaEffect.cutLife(Math.min(keep, before));
    unit.world().resetableReleased(unit, areaEffectName, before, areaEffect.getCountdown());
  }

  /** The area effect by its id among the live and the queued objects, or null. */
  private BattleEntity live() {
    return areaEffectId == NONE ? null : unit.world().liveOrQueued(areaEffectId);
  }
}
