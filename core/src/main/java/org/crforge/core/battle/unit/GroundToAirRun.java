package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.GroundToAir;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One run of a ground-to-air action on a character: a phase and its counter, and the flying height
 * of the row the character had as it started. A ground character climbs (1) for the transition, is
 * held at the height (2) for the whole less two transitions, and would then come back down (3),
 * which is refused. Each start and each step that changes the phase is told to the battle's
 * observers.
 *
 * <p>Each climbing step raises FORCE_IS_AIR and the row's climbing tags and, for a character whose
 * movement component is on, pushes the change from the row height to the climb's height at that
 * step, with the row's flying height as the push's floor: 0 at the first step, then a share of the
 * flying height that grows by a transition's share of it each 50 ms, and the whole height once the
 * counter is out. The step that finds the counter out turns to the hold without taking 50 ms off:
 * the path is reset when the row asks for it and the movement component is on, the hold's counter
 * is the whole less two transitions, and the row's action at the height is scheduled on the
 * character with the character as its cause. Each held step raises FORCE_IS_AIR and the row's held
 * tags and, for a character with a movement component, pushes the flying height.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start on a ground character, the climb's tags, heights and"
            + " pushes in 32-bit arithmetic, the turn at the height (the path reset, the hold's"
            + " counter and the scheduled action) and the hold's tags and pushes; held by"
            + " ability_hero_wizard. Refused: the descent.")
final class GroundToAirRun extends ActionInstance {

  /** Milliseconds one step takes off the counter. */
  private static final int STEP_MS = 50;

  static final int CLIMBING = 1;
  static final int HELD = 2;

  private final GroundToAir row;
  private final CharacterEntity unit;

  /** The flying height of the character's row as the run started, which the pushes start from. */
  private final int rowHeight;

  /** What is left of the phase, in milliseconds. */
  private int counter;

  private int phase;

  /**
   * The start on a ground character: its flying height override takes the row's flying height, and
   * the climb starts with the whole transition left.
   */
  GroundToAirRun(GroundToAir row, CharacterEntity unit) {
    super(row);
    this.row = row;
    this.unit = unit;
    unit.setFlyingHeightOverride(row.getFlyingHeight());
    rowHeight = unit.getData().flyingHeight();
    if (unit.getView().isAir()) {
      // An owner in the air starts from its live height against the row's: held at once, or
      // climbing the rest of the way, none of which a reference holds.
      throw new UnsupportedOperationException(
          row.name() + " lifts " + unit.name() + ", already in the air, which is not modelled");
    }
    phase = CLIMBING;
    counter = row.getTransitionDurationMs();
  }

  int phase() {
    return phase;
  }

  int counter() {
    return counter;
  }

  @Override
  protected void update(ActionHolder holder) {
    int phaseBefore = phase;
    int counterBefore = counter;
    List<Integer> pushes = new ArrayList<>();
    switch (phase) {
      case CLIMBING -> climb(holder, pushes);
      case HELD -> hold(pushes);
      default -> throw new IllegalStateException("phase " + phase);
    }
    unit.world().groundToAirStepped(unit, phaseBefore, phase, counterBefore, counter, pushes);
  }

  /**
   * The climb: the tags, the height for the counter pushed against the row height, and at the end
   * of the counter the turn to the hold.
   */
  private void climb(ActionHolder holder, List<Integer> pushes) {
    unit.raiseWatched(unit.world().forceIsAir() | row.getToAirTags());
    int t = row.getTransitionDurationMs();
    int height = row.getFlyingHeight();
    int now;
    if (counter < 0) {
      now = height;
    } else if (counter > t) {
      now = 0;
    } else {
      now = divide(-(height * counter), t) + height;
    }
    boolean moving = unit.movementOn();
    if (moving) {
      unit.pushHeight(now - rowHeight, height);
      pushes.add(now - rowHeight);
    }
    if (counter > 0) {
      counter -= STEP_MS;
      return;
    }
    phase = HELD;
    if (moving && row.isResetPathInAir()) {
      unit.resetRoute();
    }
    counter = row.getTotalDurationMs() - 2 * t;
    if (row.getOnFlyHeightReached() != null) {
      holder.schedule(row.getOnFlyHeightReached(), ActionHolder.OWN_DELAY, false, holder);
    }
  }

  /** The hold: the tags and the flying height pushed; at the end of its counter, the descent. */
  private void hold(List<Integer> pushes) {
    unit.raiseWatched(unit.world().forceIsAir() | row.getOnAirTags());
    if (unit.hasMovementComponent()) {
      unit.pushHeight(row.getFlyingHeight(), row.getFlyingHeight());
      pushes.add(row.getFlyingHeight());
    }
    if (counter > 0) {
      counter -= STEP_MS;
      return;
    }
    // The descent's height, its landing relocation and its action on the ground are held by no
    // reference.
    throw new UnsupportedOperationException(
        row.name() + " brings " + unit.name() + " back down, which is not modelled");
  }

  /** A 32-bit signed division as the hardware does it: truncated, and 0 for a zero divisor. */
  private static int divide(int dividend, int divisor) {
    return divisor == 0 ? 0 : dividend / divisor;
  }
}
