package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.WarpCharacter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.ReferenceValidator;

/**
 * One run of a flying warp: a character flown to an injected target, as the Mega Minion hero's
 * hand-over launches it. It keeps the target, the destination and the speed.
 *
 * <p><b>The start</b>, as the hand-over lists it: the destination is the target's position, or the
 * last position the hand-over recorded for it when there is none; the row's offsets are added, the
 * length one negated for side 1; the pending damage on its way to the unit is reset when the row
 * asks for it.
 *
 * <p><b>Each step</b>, from the holder's run pass: a target still in the battle is followed, the
 * destination recomputed from its position and the offsets. With the squared distance left beyond
 * the squared braking distance - the distance the speed covers while it slows down by the
 * acceleration a step, {@code n * v - n * a * (n - 1) / 2} with {@code n} the steps to stop, all in
 * 32-bit arithmetic - the speed grows by the acceleration up to the row's speed; within it, it
 * shrinks by the acceleration, not below 0. At speed 0, or with the destination nearer than one
 * step, the unit arrives; otherwise it moves one step of that length toward the destination, raises
 * NO_ATTACK, DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS, NO_DAMAGE, UNTARGETABLE and WARP for the
 * next step, and is turned to the destination.
 *
 * <p><b>The arrival</b>: the unit is placed on the destination; its route is emptied when the row
 * resets it; its reference is dropped when the row resets the target, else, when the row keeps the
 * target, it becomes the warp's target if the validator's re-check accepts it and is given up when
 * the target is gone or rejected; the row's end action is scheduled on the unit, the unit its
 * cause; the run finishes.
 *
 * <p>A leave notice of the target drops it: the unit flies on to the last destination.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's destination and offsets and the pending damage"
            + " reset, each step's follow, speed and braking in 32-bit arithmetic, the step, the"
            + " five tags and the turn, the arrival's placement, route reset, kept target and end"
            + " action, and the target's leave notice; held by"
            + " ability_hero_mega_minion_vs_musketeer. Not modelled: the two effects, which only"
            + " show something.")
final class WarpRun extends ActionInstance {

  /** The tags each step raises for the next step. */
  private long stepTags() {
    EntityFlags bits = unit.getView().getFlagBits();
    return bits.noAttack()
        | bits.disablePhysical()
        | bits.noDamage()
        | bits.untargetable()
        | bits.warp();
  }

  private final WarpCharacter row;
  private final WarpCharacter.Flight flight;
  private final CharacterEntity unit;

  /** The injected target, or null once it is gone or when none was injected. */
  private WorldEntity target;

  /** The destination along the width and the length. */
  private int destX;

  private int destY;

  /** The speed, in units a step. */
  private int speed;

  /**
   * Builds the run and starts it.
   *
   * @param row the warp's row, with its flight columns
   * @param unit the character it flies
   * @param target the injected target, or null for none
   * @param lastX the target's last recorded position along the width
   * @param lastY the target's last recorded position along the length
   */
  WarpRun(WarpCharacter row, CharacterEntity unit, WorldEntity target, int lastX, int lastY) {
    super(row);
    this.row = row;
    this.flight = row.getFlight();
    this.unit = unit;
    this.target = target;
    // InjectedCharacter: the target's position now, or the last one recorded for it.
    destX = target != null ? target.getView().getX() : lastX;
    destY = target != null ? target.getView().getY() : lastY;
    addOffsets();
    if (row.getColumns().resetPendingDamage()) {
      unit.world().resetPendingDamageAtWarp(unit, row.name());
    }
  }

  /** The row's offsets onto the destination, the length one negated for side 1. */
  private void addOffsets() {
    destX += flight.offsetX();
    destY += unit.side() == 0 ? flight.offsetY() : -flight.offsetY();
  }

  @Override
  protected void objectLeft(int leftId) {
    if (target != null && target.getId() == leftId) {
      target = null;
    }
  }

  @Override
  protected void update(ActionHolder holder) {
    if (target != null) {
      destX = target.getView().getX();
      destY = target.getView().getY();
      addOffsets();
    }
    int acc = flight.acceleration();
    int top = flight.speedPerStep();
    int[] vec = {destX - unit.getView().getX(), destY - unit.getView().getY()};
    int left = FixedMath.guardedSumOfSquares(vec[0], vec[1]);
    int cur = speed;
    // The steps the speed takes to brake to 0, and the distance it covers meanwhile, squared.
    int steps = FixedMath.divOrZero(cur + acc - 1, acc);
    int brake = steps * cur - FixedMath.divOrZero(steps * acc * (steps - 1), 2);
    brake = brake * brake;
    int next;
    if (left > brake) {
      next = cur + acc < top ? cur + acc : top;
    } else {
      next = Math.max(cur - acc, 0);
    }
    speed = next;
    if (next == 0 || left < next * next) {
      arrive(holder);
      return;
    }
    FixedMath.normalize(vec, next);
    unit.warpTo(vec[0] + unit.getView().getX(), vec[1] + unit.getView().getY());
    unit.raiseWarpTags(stepTags());
    unit.faceToward(destX, destY);
  }

  /** The arrival: placed on the destination, the route, the target and the end action. */
  private void arrive(ActionHolder holder) {
    unit.warpTo(destX, destY);
    if (row.getColumns().resetPath()) {
      unit.resetRoute();
    }
    if (row.getColumns().resetTarget()) {
      unit.resetTargetAfterWarp();
    } else if (flight.forceKeepTarget()) {
      WorldEntity keep =
          target != null
                  && unit.getSelection()
                      .validate(target.getTargetView(), ReferenceValidator.MODE_RECHECK)
              ? target
              : null;
      unit.warpReference(keep);
    }
    if (flight.onWarpEnd() != null) {
      BattleAction end =
          unit.world().getActions().build(flight.onWarpEnd(), unit.world().binding(unit));
      holder.schedule(end, ActionHolder.OWN_DELAY, false, holder);
    }
    finish();
  }
}
