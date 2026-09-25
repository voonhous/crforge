package org.crforge.core.battle;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The battle's random source: a 32-bit xorshift with the shifts 13, 17 and 5, the middle one
 * arithmetic, and one shared state from which every draw of the battle is taken in turn.
 *
 * <p>A draw for a range below one returns 0 and leaves the state alone. Otherwise a state of 0 is
 * treated as -1, the state is stepped, and the draw is the step's magnitude modulo the range, both
 * taken as unsigned: the magnitude of the lowest integer is itself, read as 2147483648.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the step, the zero-state rule, a range below one drawing nothing, and the"
            + " unsigned magnitude and remainder. Held by recorded draws from seven states over six"
            + " ranges. Not settled: the battle's seed and the order of the draws within a tick.")
public final class BattleRandom {

  /** The state the next draw steps. */
  @Getter private int state;

  /**
   * @param state the state to draw from
   */
  public BattleRandom(int state) {
    this.state = state;
  }

  /**
   * Draws a value in {@code [0, range)}.
   *
   * @param range the exclusive upper bound; below one the draw returns 0 without stepping
   */
  public int next(int range) {
    if (range < 1) {
      return 0;
    }
    int s = state == 0 ? -1 : state;
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    state = s;
    int magnitude = s < 0 ? -s : s;
    return Integer.remainderUnsigned(magnitude, range);
  }
}
