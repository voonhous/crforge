package org.crforge.core.pathfinding.state;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * The hide handler of an entity that hides while it does not attack, as the Tesla does: one step of
 * its hide counter per state visit, from the end of its deploy on, and the test of whether that
 * counter has it hidden.
 *
 * <p>The counter is 0 while the entity is up. Outside the attacking and deploying states it climbs
 * by the step to the hide time and stays there, and the entity is hidden exactly there. Attacking,
 * it climbs from the hide time on through the hide time plus the up time and wraps to 0, up again;
 * attacking while still going down, it turns back into a negative count towards 0, and a building
 * more than half-way down rises at once. A step of 0, under a stun that stops time, holds the
 * counter. A building plays its hide effect as it starts to hide and rises as it starts to come up;
 * both effects only show something, and beside each the character schedules its row's action on
 * itself. The rise's area object and push are refused with the row that sets them.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the counter's step in every state, the turn back from part-way down, the"
            + " wrap, the step of 0, the hide start and the rise of a building, and the hidden"
            + " test. Held by tesla_giant_passing, tesla_hidden_spells and tesla_ev1_knights."
            + " Not modelled: hiding before the first hit, and the rise's area object and push,"
            + " which no shipped row sets.")
public final class HideHandler {

  /** The effect a building plays as it starts to hide. */
  public static final String HIDE_EFFECT = "HideEffect";

  /** The effect a building plays as it rises. */
  public static final String APPEAR_EFFECT = "AppearEffect";

  private HideHandler() {
    // Utility class
  }

  /**
   * One visit of the hide counter.
   *
   * @param counter the counter before the visit
   * @param state the state the state visit has reached
   * @param step the visit's step: 50 without a speed buff, 0 under a stun that stops time
   * @param hidesWhenNotAttacking whether the row hides while it does not attack
   * @param hideTimeMs the counter's value at which the entity is hidden
   * @param upTimeMs the time it takes to come back up from hidden
   * @param building whether the entity is a building, which alone plays the effects
   * @param effects the effects the visit plays, appended in order
   * @return the counter after the visit
   */
  public static int visit(
      int counter,
      int state,
      int step,
      boolean hidesWhenNotAttacking,
      int hideTimeMs,
      int upTimeMs,
      boolean building,
      List<String> effects) {
    boolean hide =
        hidesWhenNotAttacking
            && state != GridEntityState.ATTACKING
            && state != GridEntityState.DEPLOYING;
    if (step == 0) {
      return counter;
    }
    int start = counter;
    int stored = counter;
    int period = hideTimeMs + upTimeMs;
    int next = start + step;
    // Coming up from part-way down and past 0: counted again from 0, a whole period on.
    if (start < 0 && next >= 0) {
      start = 0;
      next = next + period;
      stored = 0;
    }
    if (next < 0) {
      if (!hide) {
        return next;
      }
      next = -next;
      stored = Math.max(next - step, 0);
      return goingDown(stored, next, period, hideTimeMs, building, effects);
    }
    int current = stored;
    boolean keepsOn = start < hideTimeMs && current >= 1 ? hide : true;
    if (!keepsOn) {
      // Going down and attacking: turn back up, rising at once from more than half-way down.
      if (building && hideTimeMs < next << 1) {
        effects.add(APPEAR_EFFECT);
      }
      int back = -next;
      // The floor at minus the hide time is the standard game's; the value is read below only
      // when the next one is 0, where it cannot bind.
      current = Math.max(back - step, -hideTimeMs);
      stored = current;
      if (next != 0) {
        return back;
      }
    }
    if (hide) {
      return goingDown(stored, next, period, hideTimeMs, building, effects);
    }
    if (current == 0) {
      return 0;
    }
    if (next <= period) {
      return step(stored, next, period, hideTimeMs, building, effects);
    }
    return 0;
  }

  /**
   * Whether a hiding entity's counter has it hidden: exactly at the hide time, so while it goes
   * down, comes up or is up every asker sees it.
   */
  public static boolean hidden(int counter, int hideTimeMs) {
    return counter == hideTimeMs;
  }

  /** Going down: the counter stops at the hide time. */
  private static int goingDown(
      int stored, int next, int period, int hideTimeMs, boolean building, List<String> effects) {
    if (next < hideTimeMs) {
      return step(stored, next, period, hideTimeMs, building, effects);
    }
    if (stored <= hideTimeMs) {
      return hideTimeMs;
    }
    return step(stored, next, period, hideTimeMs, building, effects);
  }

  /**
   * The effects of a building at 0, where it starts to hide, and at the hide time, where it rises;
   * then the new value, the next one taken modulo the period.
   */
  private static int step(
      int stored, int next, int period, int hideTimeMs, boolean building, List<String> effects) {
    if (building) {
      if (stored == 0) {
        effects.add(HIDE_EFFECT);
      }
      if (stored == hideTimeMs) {
        effects.add(APPEAR_EFFECT);
      }
    }
    int quotient = period == 0 ? 0 : next / period;
    return next - quotient * period;
  }
}
