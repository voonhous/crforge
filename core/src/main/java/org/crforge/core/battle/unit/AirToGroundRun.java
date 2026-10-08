package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.AirToGround;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One run of an air-to-ground action on a character or a tower: a phase and its counter. On the
 * ground (phase 0) the unit is held for the whole duration; an air unit descends (1) for the
 * transition, is held on the ground (2) for the whole less two transitions, and climbs (3) for the
 * transition. Each step of a phase pushes the height change of that step, for a unit with a
 * movement component, and every held step raises FORCE_IS_GROUND, on the ground only when the row
 * asks for it. Each start, phase change, finish and re-trigger is told to the battle's observers.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start, the four phases, their pushes and FORCE_IS_GROUND,"
            + " the re-trigger and the finish; held by vines_group and vines_tower. The climb's one"
            + " step is held by BattleAirToGroundTest and the re-trigger of a hold on the ground by"
            + " BattleShapeSelectorTest, by a selector written in Vines' form; the re-trigger of a"
            + " hold in the air or of a climb by"
            + " nothing. Refused: the path reset of an air unit at the end.")
final class AirToGroundRun extends ActionInstance {

  /** Milliseconds one step takes off the counter. */
  private static final int STEP_MS = 50;

  static final int GROUND = 0;
  static final int DESCENDING = 1;
  static final int HELD = 2;
  static final int CLIMBING = 3;

  private final AirToGround row;
  private final WorldEntity unit;

  /** The height the unit flies at, or -1 for a ground unit. */
  private int height = -1;

  /** What is left of the phase, in milliseconds. */
  private int counter = -1;

  private int phase = GROUND;

  /**
   * The start: an air unit descends from its flying height, a unit flying at a height but on the
   * ground layer is held at once, and a ground unit is held where it is.
   */
  AirToGroundRun(AirToGround row, WorldEntity unit) {
    super(row);
    this.row = row;
    this.unit = unit;
    // The height a ground-to-air run left on the unit when above 0, else its row's.
    int flying =
        unit.flyingHeightOverride() > 0
            ? unit.flyingHeightOverride()
            : unit.getData().flyingHeight();
    if (flying >= 1) {
      height = flying;
      if (unit.getView().isAir()) {
        phase = DESCENDING;
        counter = row.getTransitionDurationMs();
      } else {
        phase = HELD;
        counter = row.getTotalDurationMs() - row.getTransitionDurationMs();
      }
    } else {
      phase = GROUND;
      counter = row.getTotalDurationMs();
    }
    // The start schedules the action once on the ground at once unless the unit descends; no
    // reference holds a run with that action that starts on the ground.
    if (phase != DESCENDING && row.getOnGround() != null) {
      throw new UnsupportedOperationException(
          row.name()
              + " starts on the ground on "
              + unit.name()
              + ", scheduling its action once on the ground at the start, which is not modelled");
    }
  }

  int phase() {
    return phase;
  }

  int counter() {
    return counter;
  }

  int height() {
    return height;
  }

  @Override
  protected void update(ActionHolder holder) {
    int phaseBefore = phase;
    int counterBefore = counter;
    List<Integer> pushes = new ArrayList<>();
    switch (phase) {
      case GROUND -> {
        if (counter <= 0) {
          end();
        } else {
          push(0, pushes);
          if (row.isAllowIsGroundTagOnIdle()) {
            unit.raiseForceIsGround();
          }
          counter -= STEP_MS;
        }
      }
      case DESCENDING -> descend(holder, pushes);
      case HELD -> hold(pushes);
      case CLIMBING -> {
        int t = row.getTransitionDurationMs();
        int now;
        if (counter < 0) {
          now = height;
        } else if (counter > t) {
          now = 0;
        } else {
          now = height + -height * counter / t;
        }
        push(now - height, pushes);
        if (counter <= 0) {
          end();
        } else {
          counter -= STEP_MS;
        }
      }
      default -> throw new IllegalStateException("phase " + phase);
    }
    if (phase != phaseBefore || isFinished()) {
      unit.world()
          .airToGroundStepped(
              unit, phaseBefore, phase, counterBefore, counter, isFinished(), pushes);
    }
  }

  /**
   * The descent: the height from the flying height toward 0 over the transition; at its end the
   * hold for the whole less two transitions, and the action once on the ground scheduled on the
   * unit with the unit as its cause.
   */
  private void descend(ActionHolder holder, List<Integer> pushes) {
    int t = row.getTransitionDurationMs();
    int now;
    if (counter < 0) {
      now = 0;
    } else if (counter > t) {
      now = height;
    } else {
      now = counter * height / t;
    }
    push(now - height, pushes);
    if (counter > 0) {
      counter -= STEP_MS;
      return;
    }
    phase = HELD;
    counter = row.getTotalDurationMs() - 2 * t;
    if (row.getOnGround() != null) {
      holder.schedule(row.getOnGround(), ActionHolder.OWN_DELAY, false, holder);
    }
  }

  /** The hold: FORCE_IS_GROUND and the height at 0; at its end the climb. */
  private void hold(List<Integer> pushes) {
    unit.raiseForceIsGround();
    push(-height, pushes);
    if (counter > 0) {
      counter -= STEP_MS;
      return;
    }
    phase = CLIMBING;
    counter = row.getTransitionDurationMs() - STEP_MS;
  }

  /** Pushes a height change to a unit with a movement component. */
  private void push(int delta, List<Integer> pushes) {
    if (unit.hasMovementComponent()) {
      unit.pushHeight(delta, 0);
      pushes.add(delta);
    }
  }

  /** The finish; an air unit's path would be reset as it ends, which is not modelled. */
  private void end() {
    finish();
    if (row.isResetPathAtEnd() && height >= 1 && unit.hasMovementComponent()) {
      throw new UnsupportedOperationException(
          row.name() + " ends on " + unit.name() + ", resetting its path, which is not modelled");
    }
  }

  /** A second start of the row: the phase's counter starts over, a climb turning into a descent. */
  @Override
  protected void retrigger(ActionHolder holder) {
    switch (phase) {
      case GROUND -> counter = row.getTotalDurationMs();
      case HELD -> counter = row.getTotalDurationMs() - row.getTransitionDurationMs();
      case CLIMBING -> {
        counter = row.getTransitionDurationMs() - counter;
        phase = DESCENDING;
      }
      default -> {
        // A descent carries on as it was.
      }
    }
    unit.world().airToGroundRetriggered(unit, row.name(), phase, counter);
  }
}
