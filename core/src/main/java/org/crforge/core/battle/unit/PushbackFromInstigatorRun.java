/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.DoPushbackFromInstigator;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One run of a push away from the cause on a character: the tick it is due on and the cause it
 * keeps. The step on exactly the due tick pushes and finishes the run; see {@link
 * DoPushbackFromInstigator} for the push and what follows it.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled from the listing: the due tick from the start, the exact test, the push and"
            + " its schedules; held by ability_hero_giant_slap. Refused: a cause gone by the push"
            + " and a run let go before its push.")
final class PushbackFromInstigatorRun extends ActionInstance {

  private final DoPushbackFromInstigator row;
  private final CharacterEntity unit;
  private final WorldEntity instigator;

  /** The battle tick the push is due on. */
  private final int due;

  /**
   * The start: the due tick from the battle tick, the cause kept.
   *
   * @param row the push
   * @param unit the character it runs on
   * @param instigator what caused it
   */
  PushbackFromInstigatorRun(
      DoPushbackFromInstigator row, CharacterEntity unit, WorldEntity instigator) {
    super(row);
    this.row = row;
    this.unit = unit;
    this.instigator = instigator;
    this.due = row.dueTick(unit.world().tick());
  }

  @Override
  protected void update(ActionHolder holder) {
    if (unit.world().tick() != due) {
      return;
    }
    push();
    finish();
  }

  /** The push and the actions its answer schedules. */
  private void push() {
    DoPushbackFromInstigator.Columns c = row.getColumns();
    if (!instigator.actionAlive()) {
      throw new UnsupportedOperationException(
          row.name() + " pushes after its cause " + instigator.name() + " died, not modelled");
    }
    boolean pushed = false;
    if (unit.hasMovementComponent() && (unit.getView().getFlags() & c.disallowTags()) == 0) {
      // The point lies on the owner's line along the length, the offset beside it toward the
      // side of the arena's centre line the cause stands on.
      int centre = unit.world().getTileMap().width() * 250;
      int x = unit.getView().getX() + (centre > instigator.x() ? -1 : 1) * c.directionalOffset();
      int y = unit.getView().getY();
      pushed = unit.pushedFromInstigator(x, y, c) == 1;
    }
    if (pushed) {
      schedule(instigator, unit, c.successOnInstigator());
      schedule(unit, instigator, c.successAction());
      if (c.resetAvoidance()) {
        unit.getUnit().movement().setAvoidanceBlend(0);
      }
    } else {
      schedule(instigator, unit, c.failureOnInstigator());
    }
  }

  /** Schedules a row, built for the object it runs on, with its cause; nothing for no row. */
  private void schedule(WorldEntity target, WorldEntity cause, String name) {
    if (name == null) {
      return;
    }
    BattleAction action = unit.world().getActions().build(name, unit.world().binding(target));
    target.actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, cause.actionHolder());
  }

  @Override
  protected void objectLeft(int leftId) {
    if (!isFinished() && leftId == instigator.getId()) {
      throw new UnsupportedOperationException(
          row.name() + "'s cause " + instigator.name() + " left before its push, not modelled");
    }
  }

  @Override
  protected void stop(ActionHolder holder) {
    if (!isFinished()) {
      throw new UnsupportedOperationException(
          row.name() + " on " + unit.name() + " was let go before its push, not modelled");
    }
  }
}
