package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Baby Dragon's wind: a run on its owner that makes its area effect at the owner's
 * point, moved by its offsets - the one along the length turned toward the enemy side - for the
 * owner's side and level, the owner its parent and, for a row that follows its parent, the object
 * it follows with the same offsets. The run lasts until its area effect has left. A singleton row
 * started again while the run lasts gives the area effect its whole lifetime back and makes no
 * second one; as the owner leaves, a live area effect keeps no more of its life than the row's
 * stay.
 *
 * <p>Refused as the row is built: destroying the area effect while its owner's combat is disabled,
 * and the shared columns its run does not read.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the area effect made at the offset point and following with the"
            + " offsets, the lifetime given back by a second start, the run's end after the area"
            + " effect leaves, and the life cut to the stay as the owner leaves; held by"
            + " baby_dragon_ev1_wind. Refused: the destruction while the owner's combat is"
            + " disabled.")
public final class SpawnResetableAreaEffect extends RowAction {

  /** The area effect row it makes. */
  @Getter private final String areaEffect;

  /** How far along the width from the owner it is made. */
  @Getter private final int offsetX;

  /** How far along the length from the owner it is made, toward the enemy side. */
  @Getter private final int offsetY;

  /** The most life a live area effect keeps as the owner leaves; below 0 for no cut. */
  @Getter private final int stayAliveAfterParentDiesMs;

  /**
   * @param row the row's shared columns
   * @param areaEffect the area effect row it makes
   * @param offsetX how far along the width from the owner it is made
   * @param offsetY how far along the length, toward the enemy side
   * @param stayAliveAfterParentDiesMs the most life the area effect keeps as the owner leaves
   */
  public SpawnResetableAreaEffect(
      ActionRow row, String areaEffect, int offsetX, int offsetY, int stayAliveAfterParentDiesMs) {
    super(row);
    this.areaEffect = areaEffect;
    this.offsetX = offsetX;
    this.offsetY = offsetY;
    this.stayAliveAfterParentDiesMs = stayAliveAfterParentDiesMs;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return holder
        .getOwner()
        .resetableAreaEffect(
            this, holder.passPhase(), instigator == null ? null : instigator.getOwner());
  }
}
