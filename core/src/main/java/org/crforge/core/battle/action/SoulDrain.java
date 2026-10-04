package org.crforge.core.battle.action;

import java.util.function.IntSupplier;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A soul's flight to the object the run is on, as each skeleton's soul flies to the evolved Witch:
 * the run takes the flight time in whole ticks - the row's constant flight time over 50 ms, toward
 * zero - and ends on the step that reaches the battle tick it started on plus that many. Then it
 * schedules the row's action on its object with its own delay, its object as the cause, and
 * finishes. Everything else the row sets - the soul's effects, its wobble, its waits, its pivot -
 * only shows the flight.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the end tick from the start's battle tick and the flight time"
            + " over 50, the test on each step and the action on the object, itself the cause, as"
            + " the run finishes; held by evo_witch_vs_musketeer.")
public final class SoulDrain extends RowAction {

  /** Milliseconds one battle tick takes. */
  private static final int TICK_MS = 50;

  /** The flight's duration in milliseconds. */
  @Getter private final int flightMs;

  /** The action scheduled as the soul arrives, or null. */
  @Getter private final BattleAction onTargetReached;

  /**
   * @param row the row's shared columns
   * @param flightMs the flight's duration in milliseconds
   * @param onTargetReached the action scheduled as the soul arrives, or null
   */
  public SoulDrain(ActionRow row, int flightMs, BattleAction onTargetReached) {
    super(row);
    this.flightMs = flightMs;
    this.onTargetReached = onTargetReached;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    IntSupplier clock = holder.getOwner().actionClock(this);
    return new Run(clock, clock.getAsInt() + flightMs / TICK_MS);
  }

  /** One flight: the battle tick it ends on. */
  private final class Run extends ActionInstance {

    private final IntSupplier clock;
    private final int endTick;

    private Run(IntSupplier clock, int endTick) {
      super(SoulDrain.this);
      this.clock = clock;
      this.endTick = endTick;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (endTick > clock.getAsInt()) {
        return;
      }
      if (onTargetReached != null) {
        holder.schedule(onTargetReached, ActionHolder.OWN_DELAY, false, holder);
      }
      finish();
    }
  }
}
