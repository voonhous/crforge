package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.Knockback;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;

/**
 * One run of a knock on a character: its counter, from the row's duration, and the height it last
 * pushed.
 *
 * <p>Each update raises DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS, and FORCE_IS_AIR while more
 * than 149 ms are left. For a unit with a movement component and a height it pushes the arc's next
 * height, with that height as the push's floor: half the duration rising to the top and half
 * falling. Once the counter is at 0 or below the unit lands - its route reset - and the run
 * finishes, the counter still taken down by 50 ms.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's tags and counter, each update's tags, the arc's"
            + " height in 32-bit arithmetic with its two divisions by 100, the landing's route"
            + " reset and the finish; held by mega_knight_ev1_uppercut. Refused: a unit jumping,"
            + " dashing, charging or following a removed building, a clone, a rider or carrier,"
            + " and one with an ability.")
final class KnockbackRun extends ActionInstance {

  /** Milliseconds one update takes off the counter. */
  private static final int STEP_MS = 50;

  /** FORCE_IS_AIR is raised while the counter is above this. */
  private static final int AIR_UNTIL = 149;

  private final Knockback row;
  private final CharacterEntity unit;

  /** What is left of the knock, in milliseconds. */
  private int counter;

  /** The height the last update pushed. */
  private int height;

  /**
   * The start: the ability postponed while the run is listed, the counter from the duration, and
   * FORCE_IS_AIR with DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS raised.
   *
   * @param row the knock
   * @param unit the unit it runs on
   * @param phase the phase of the pending pass that ran it
   * @param instigator what caused it, or null
   */
  KnockbackRun(Knockback row, CharacterEntity unit, int phase, WorldEntity instigator) {
    super(row);
    this.row = row;
    this.unit = unit;
    int state = unit.getView().getState();
    if (state == GridEntityState.FOLLOWING_REMOVED_BUILDING
        || state == GridEntityState.JUMPING
        || state == GridEntityState.DASHING) {
      throw new UnsupportedOperationException(
          row.name() + " knocks " + unit.name() + " in state " + state + ", not modelled");
    }
    addTags(EntityFlags.ABILITY_POSTPONED);
    counter = row.getDurationMs();
    unit.startLayering();
    unit.raiseWatched(unit.world().forceIsAir() | EntityFlags.DISABLE_PHYSICAL);
    unit.world().knockbackStarted(unit, row.name(), phase, instigator, counter);
  }

  @Override
  protected void update(ActionHolder holder) {
    int before = counter;
    long tags = EntityFlags.DISABLE_PHYSICAL;
    if (counter > AIR_UNTIL) {
      tags |= unit.world().forceIsAir();
    }
    unit.raiseWatched(tags);
    boolean moving = unit.hasMovementComponent();
    if (moving && unit.getUnit().movement().getChargeProgress() != MovementState.CHARGE_INACTIVE) {
      throw new UnsupportedOperationException(
          row.name() + " knocks " + unit.name() + " while it charges, not modelled");
    }
    if (unit.getView().getState() == GridEntityState.FOLLOWING_REMOVED_BUILDING) {
      throw new UnsupportedOperationException(
          row.name() + " knocks " + unit.name() + " following a removed building, not modelled");
    }
    if (moving && row.getHeight() >= 1) {
      height = arc(row.getDurationMs(), row.getHeight(), counter, height);
      unit.pushHeight(height, height);
    }
    if (counter <= 0) {
      if (moving) {
        unit.resetRoute();
      }
      finish();
    }
    counter -= STEP_MS;
    unit.world().knockbackStepped(unit, before, counter, height, tags, isFinished());
  }

  /**
   * The height an update pushes: the duration's half, at least 1, in steps of 50 ms; the height
   * over those steps a step's share; while more than the half is left the rise adds (190 - twice
   * the percent of the half elapsed) of a share, in hundredths, and from there the fall takes off
   * (twice the percent of the half gone plus 10). Both divisions by 100 are the multiply-and-shift
   * the code does, rounding toward zero, and the share an unsigned division.
   *
   * @param duration the knock's duration
   * @param top the arc's top
   * @param counter what is left of the knock
   * @param previous the height the last update pushed
   */
  static int arc(int duration, int top, int counter, int previous) {
    int half = duration / 2;
    int den = half > 1 ? half : 1;
    int steps = (int) ((Integer.toUnsignedLong(den) * 0x51eb851fL) >>> 36);
    int per = steps == 0 ? 0 : (int) (Integer.toUnsignedLong(top) / Integer.toUnsignedLong(steps));
    int step;
    if (den >= counter) {
      int q = (den - counter) * 100 / den;
      step = divideBy100((q * 2 + 10) * per, 0xae147ae1);
    } else {
      int q = (duration - counter) * 100 / den;
      step = divideBy100((190 - q * 2) * per, 0x51eb851f);
    }
    return step + previous;
  }

  /**
   * A division by 100, or by -100 with the negative magic number, as a signed multiply keeping the
   * high bits and the sign's carry.
   */
  private static int divideBy100(int x, int magic) {
    long product = (long) x * magic;
    return (int) ((product >> 37) + (product >>> 63));
  }
}
