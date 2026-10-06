package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.MegaKnightUppercut;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.RangeTest;

/**
 * One run of the evolved Mega Knight's uppercut on the unit.
 *
 * <p>As it starts the unit carries NO_ATTACK and NO_MOVE for one step and the run keeps the object
 * the unit's targeting component has as its target; an object without a movement component ends the
 * run at once. Its first update pushes that target: the point it is pushed away from stands just
 * behind it on the line from the nearest tower of its own side - the side's princess towers, then
 * the king facing the unit's side - so the push drives it toward that tower, through the pushback
 * entry past every gate of the request; then the row's action is scheduled on it with the unit as
 * the cause, a push the entry took clears the target's avoidance blend unless the row turns that
 * off, and the unit carries NO_ATTACK, NO_MOVE and LOCK_TARGET for one step and is held for the
 * follow-up delay. A target with no movement component switched on, or one following a removed
 * building, is not pushed and ends the run. Each later update ends the run when the unit has no
 * target or attacks another one in range; with another target out of range it marks the uppercut's
 * target in its targeting queue, at a priority the queue's flush never takes; then the delay runs
 * down by 50 ms, the unit held for one more step while any is left, and the run ends when none is.
 * A target that leaves the battle is forgotten, and the next update ends the run.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's hold and target, the push point, the push through"
            + " the entry, the action on the target, the hold for the delay, the ends, the mark"
            + " and the leave notice; held by mega_knight_ev1_uppercut. The blend cleared after a"
            + " push the entry took, on ResetAvoidanceAtPushback; held by UppercutAvoidanceTest."
            + " Refused: the start"
            + " without a current target, which reads the targeting component's previous"
            + " reference, a push point with no tower or king found, which turns to the facing,"
            + " and a target other than a character with a movement component.")
final class UppercutRun extends ActionInstance {

  /** Milliseconds one update takes off the delay. */
  private static final int STEP_MS = 50;

  /** The squared distance before any tower is found, compared unsigned. */
  private static final long UNSET = 0xffffffffL;

  private final MegaKnightUppercut row;
  private final CharacterEntity unit;

  /** The object the run pushes, or null once it has left. */
  private WorldEntity target;

  private boolean pushed;

  /** What is left of the follow-up delay, in milliseconds. */
  private int delay;

  /** The squared distance to the nearest tower found, compared unsigned, and the vector to it. */
  private long best = UNSET;

  private int vx;
  private int vy;

  /** The point this update pushed away from and the vector from the tower, or null for none. */
  private int[] pushPoint;

  /**
   * The start: the hold for one step and the target kept, the run finished for a target without a
   * movement component.
   *
   * @param row the uppercut
   * @param unit the unit it runs on
   * @param phase the phase of the pending pass that ran it
   * @param instigator what caused it, the target of the hit, or null
   */
  UppercutRun(MegaKnightUppercut row, CharacterEntity unit, int phase, WorldEntity instigator) {
    super(row);
    this.row = row;
    this.unit = unit;
    unit.raiseWatched(bits().noAttack() | bits().noMove());
    target = unit.currentTarget();
    if (target == null) {
      throw new UnsupportedOperationException(
          row.name()
              + " starts on "
              + unit.name()
              + " without a current target, which reads its previous reference, not modelled");
    }
    if (!target.hasMovementComponent()) {
      finish();
    }
    unit.world().uppercutStarted(unit, row.name(), phase, instigator, target, isFinished());
  }

  @Override
  protected void update(ActionHolder holder) {
    int before = delay;
    pushPoint = null;
    String outcome;
    if (target == null) {
      outcome = "no target";
      finish();
    } else if (!pushed) {
      pushed = true;
      if (!pushTarget()) {
        outcome = "push refused";
        finish();
      } else {
        unit.raiseWatched(bits().noAttack() | bits().noMove() | bits().lockTarget());
        delay = row.getDashFollowUpDelayMs();
        if (delay != 0) {
          outcome = "pushed";
        } else {
          outcome = "pushed, no delay";
          finish();
        }
      }
    } else {
      outcome = hold();
    }
    if (before != delay || pushPoint != null || isFinished()) {
      unit.world().uppercutStepped(unit, delay, isFinished(), outcome, pushPoint);
    }
  }

  /** A later update: the ends, the mark, and the delay run down. */
  private String hold() {
    WorldEntity current = unit.currentTarget();
    if (current == null) {
      finish();
      return "owner without a target";
    }
    if (unit.isActive(CharacterEntity.TARGETING_SLOT) && current.getId() != target.getId()) {
      if (RangeTest.referenceInRange(unit.getUnit().targeting(), current.getTargetView(), 0)) {
        finish();
        return "other target in range";
      }
      unit.markTarget(target, 0);
    }
    delay -= STEP_MS;
    if (delay > 0) {
      unit.raiseWatched(bits().noMove() | bits().noAttack());
      return "waiting";
    }
    // Without the follow-up dash, which the row refuses, the run ends with the delay.
    finish();
    return "delay over";
  }

  /**
   * The push point and the push: answers whether the push ran on a target not following a removed
   * building.
   */
  private boolean pushTarget() {
    if (!target.hasMovementComponent()) {
      return false;
    }
    int state = target.getView().getState();
    if (state == GridEntityState.FOLLOWING_REMOVED_BUILDING) {
      return false;
    }
    int tx = target.getView().getX();
    int ty = target.getView().getY();
    for (TowerEntity tower : unit.world().princessTowers(target.side())) {
      nearer(tx, ty, tower);
    }
    TowerEntity king = unit.world().kingTower(WorldEntity.opposing(unit.side()));
    if (king == null) {
      throw new UnsupportedOperationException(
          row.name() + " pushes with no king facing " + unit.name() + ", not modelled");
    }
    nearer(tx, ty, king);
    if (best == UNSET) {
      throw new UnsupportedOperationException(
          row.name() + " finds no tower to push toward, which turns to the facing, not modelled");
    }
    int px = tx;
    int py = ty;
    if ((int) best >= 1) {
      int root = FixedMath.isqrt((int) best);
      int offset = row.getPushRadiusDirectionalOffset();
      px = offset * vx / root + tx;
      py = vy * offset / root + ty;
    }
    pushPoint = new int[] {px, py, vx, vy};
    boolean ran = push(px, py);
    vx = 0;
    vy = 0;
    best = UNSET;
    return ran;
  }

  /**
   * Keeps a tower as the nearest when its squared distance is below the best, compared unsigned.
   */
  private void nearer(int tx, int ty, WorldEntity tower) {
    int dx = tx - tower.getView().getX();
    int dy = ty - tower.getView().getY();
    long squared = Integer.toUnsignedLong(dx * dx + dy * dy);
    if (best > squared) {
      best = squared;
      vx = dx;
      vy = dy;
    }
  }

  /**
   * The push through the entry, past the request's gates, then the row's action on the target with
   * the unit as the cause. A target whose movement component is off is not pushed.
   */
  private boolean push(int x, int y) {
    if (!(target instanceof CharacterEntity pushedUnit)) {
      throw new UnsupportedOperationException(
          row.name() + " pushes " + target.name() + ", which is not a character, not modelled");
    }
    if (!pushedUnit.isActive(CharacterEntity.MOVEMENT_SLOT)) {
      return false;
    }
    int entered =
        pushedUnit.pushEntry(
            x,
            y,
            row.getPushBackStrength(),
            true,
            row.isDistanceProportionalPush(),
            row.isResetPushbackIfStronger());
    BattleAction onTargets = row.getActionOnTargets();
    if (onTargets != null) {
      BattleAction built =
          unit.world().getActions().build(onTargets.name(), unit.world().binding(pushedUnit));
      pushedUnit.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, unit.actionHolder());
    }
    // A push the entry took clears the target's avoidance blend, so no push step is turned by
    // the way it was steering: it flies straight toward its tower. Only the data versions whose
    // game has the row's switch do this.
    if (entered == 1
        && unit.world().uppercutResetsAvoidance()
        && row.isResetAvoidanceAtPushback()) {
      pushedUnit.getUnit().movement().setAvoidanceBlend(0);
    }
    return true;
  }

  /** The target leaving the battle is forgotten. */
  @Override
  protected void objectLeft(int leftId) {
    if (target != null && target.getId() == leftId) {
      unit.world().uppercutTargetLeft(unit, target);
      target = null;
    }
  }

  /** Which bit of the unit's tag word each flag is. */
  private EntityFlags bits() {
    return unit.getView().getFlagBits();
  }
}
