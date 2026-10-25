/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts on its owner and holds it on the ground for a time, as Vines does: a ground
 * unit is held where it is, a hovering unit is held from the start and let go with no climb, and an
 * air unit is pulled down, held at height 0 and let climb back. While it holds, it raises
 * FORCE_IS_GROUND on its owner, under which the owner is a ground unit to every reader of its layer
 * from its next pre-hook. Its height changes are pushed to the owner, which folds them into its
 * live height at its next pre-hook. A second start of a singleton row while its run lasts starts
 * the run's phase over.
 *
 * <p>An air unit that lands at the end of its descent has the row's action once on the ground
 * scheduled on it, with itself as the cause, as the evolved Royal Hog's fall does. A unit already
 * on the ground as the run starts has it scheduled the same way at the start, and only then: an
 * evolved Royal Hog Vines or an evolved Hunter's net already holds, hit, turns into its grounded
 * form at once.
 *
 * <p>A clone is held as any other unit is, on the ground, in the air or hovering, unless the entity
 * that caused the run is a character or a tower whose own holder lists a run of the same row: the
 * run then takes that run's phase and counter, at the height the clone's row flies at. That is how
 * a Clone's creator starts the row again on the clone of a unit it holds, the unit the cause, so
 * the clone is held as its unit is and let go with it. A clone has the action once on the ground
 * only when the row lets a clone trigger its landing actions (CloneTriggersLandingActions, true
 * unless the row says not); a row that says not, as the evolved Royal Hog's fall does, schedules
 * nothing on a clone.
 *
 * <p>Refused as the row is built: the landing and landing end actions, a path reset at landing and
 * a next action. As it starts: an owner that rides another, a hovering owner a force tag already
 * holds, and an action once on the ground for a clone whose row lets a clone trigger it, none of
 * which a reference holds.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's phase from the owner's height and layer, the four"
            + " phases, the pushes, FORCE_IS_GROUND raised, the re-trigger and the finish; held"
            + " by the reference battles spell_vines_into_push and cg_vines_king_giant_minipekka,"
            + " units held, and card_Vines, a princess tower held. Held"
            + " by the tests alone: the re-trigger of a hold on the ground"
            + " (BattleShapeSelectorTest, by a selector written in Vines' form) and the climb's"
            + " one step (BattleAirToGroundTest); the re-trigger of a hold in the air or of a"
            + " climb is translated but held by nothing. The action once on the ground at the end"
            + " of a descent, and the row's tags, held by evo_royalhogs_vs_musketeer; at the start on a"
            + " unit already held on the ground, by BattleAirToGroundTest and recorded battles"
            + " of evolved Royal Hogs under Vines and an evolved Hunter's net. A hovering"
            + " owner held from the start, by BattleAirToGroundTest and recorded Vines battles on"
            + " the Ghost and its evolution, a push off the river at the hold's last step"
            + " among them. Refused: the landing and landing end actions, a path reset at"
            + " landing, a next action, the action once on the ground for a clone whose row lets a"
            + " clone trigger it, a riding"
            + " owner and a hovering owner a force tag already holds. A clone on the ground held"
            + " as any ground unit is, by BattleAirToGroundTest; recorded battles of the Skeleton"
            + " King's graveyard skeletons held run on through it. A clone in the air pulled down"
            + " as any air unit is, by BattleAirToGroundTest and recorded Vines on a cloned Baby"
            + " Dragon over its own princess tower, which it lands on; a hovering clone held from"
            + " the start, by BattleAirToGroundTest and a recorded Vines on a cloned Ghost on the"
            + " river, which no observation tells from no hold. The row started again on a"
            + " Clone's clone, its run a copy of its unit's, by recorded Clones of a Baby Dragon"
            + " Vines or an evolved Hunter's net holds and of falling and grounded evolved Royal"
            + " Hogs, whose clones skip the landing actions.")
public final class AirToGround extends RowAction {

  /** How long the descent and the climb each take, in milliseconds. */
  @Getter private final int transitionDurationMs;

  /** How long the whole run takes, in milliseconds. */
  @Getter private final int totalDurationMs;

  /** True when a ground unit held in place is forced onto the ground layer too. */
  @Getter private final boolean allowIsGroundTagOnIdle;

  /** True when an air unit's path is reset as the run ends. */
  @Getter private final boolean resetPathAtEnd;

  /** The action scheduled on the owner once it is on the ground, or null for none. */
  @Getter private final BattleAction onGround;

  /**
   * True when a clone the run holds has the action once on the ground scheduled as any unit does;
   * false when a clone never has it.
   */
  @Getter private final boolean cloneTriggersLandingActions;

  /**
   * @param row the row's shared columns
   * @param transitionDurationMs how long the descent and the climb each take
   * @param totalDurationMs how long the whole run takes
   * @param allowIsGroundTagOnIdle true when a ground unit is forced onto the ground layer too
   * @param resetPathAtEnd true when an air unit's path is reset as the run ends
   * @param onGround the action scheduled on the owner once it is on the ground, or null
   * @param cloneTriggersLandingActions true when a clone has the action once on the ground too
   */
  public AirToGround(
      ActionRow row,
      int transitionDurationMs,
      int totalDurationMs,
      boolean allowIsGroundTagOnIdle,
      boolean resetPathAtEnd,
      BattleAction onGround,
      boolean cloneTriggersLandingActions) {
    super(row);
    this.transitionDurationMs = transitionDurationMs;
    this.totalDurationMs = totalDurationMs;
    this.allowIsGroundTagOnIdle = allowIsGroundTagOnIdle;
    this.resetPathAtEnd = resetPathAtEnd;
    this.onGround = onGround;
    this.cloneTriggersLandingActions = cloneTriggersLandingActions;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  /** Starts the run on the holder's owner; the cause is read for a clone only. */
  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return holder.getOwner().airToGround(this, holder.passPhase(), instigator);
  }
}
