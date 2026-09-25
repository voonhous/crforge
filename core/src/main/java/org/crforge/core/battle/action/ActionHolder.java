package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Setter;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The scheduled actions of one entity: the entries waiting out their delay, and the instances of
 * the actions that last.
 *
 * <p>The holder tick drives it through {@link EntityActions}: a pending pass per phase starts the
 * due entries of that phase, the run pass after the component passes steps every listed instance,
 * and the end pass takes a tick off every waiting delay.
 *
 * <p>Scheduling an action with no delay starts it at once only inside a pending pass. Scheduled
 * anywhere else - at placement, from a component pass, from the run pass or a post-hook - it is
 * queued with no ticks left and started by the next pending pass whose phase it matches.
 *
 * <p>Two orders are the standard game's and are kept. A pending pass takes the entry it finds and
 * moves the last entry into its place, then looks at that place again, so four due entries {@code a
 * b c d} start as {@code a d c b}. The run pass removes an instance that finished in an earlier
 * step the same way, before it would step it; an instance that finishes in its own step therefore
 * stays listed, and keeps setting its tags, until the next run pass.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the three pending passes and where zero-delay actions start, the swap-with-last"
            + " order of a pending pass, the run pass after the component passes stepping every"
            + " listed instance and removing a finished one at its next pass, the tags of every"
            + " listed instance folded in whether finished or not, the delay taken off in the end"
            + " pass, and a next action scheduled alongside with the same delay. Not modelled: the"
            + " delay columns beyond zero, the pause and stop expressions, the execute condition,"
            + " a singleton re-trigger, a group's sub-actions and the removal notice to an"
            + " action's instigator.")
public class ActionHolder implements EntityActions {

  /** Milliseconds one tick takes off a queued delay. */
  private static final int TICK_MS = 50;

  /** What an observer of the holder is told as its actions start, finish and leave. */
  public interface Listener {

    /** An action started, in the pending pass of the given phase. */
    default void started(BattleAction action, int phase) {}

    /** An instance finished during its step in the run pass. */
    default void finished(ActionInstance instance) {}

    /** The run pass removed an instance that had finished. */
    default void removed(ActionInstance instance) {}
  }

  /** One queued action and the ticks left before it is due. */
  private static final class Entry {
    private final BattleAction action;
    private int ticks;

    private Entry(BattleAction action, int ticks) {
      this.action = action;
      this.ticks = ticks;
    }
  }

  private final List<Entry> pending = new ArrayList<>();
  private final List<ActionInstance> running = new ArrayList<>();

  /** The phase of the pending pass in progress, or 0 outside every pending pass. */
  private int passPhase;

  @Setter private Listener listener = new Listener() {};

  /**
   * Schedules an action, and the action scheduled alongside it.
   *
   * @param action the action
   * @param delayMs the delay before it is due; 0 or less for none
   */
  public void schedule(BattleAction action, int delayMs) {
    if (delayMs <= 0 && passPhase != 0) {
      start(action, passPhase);
    } else {
      pending.add(new Entry(action, Math.max(delayMs, 0) / TICK_MS));
    }
    if (action.nextAction() != null) {
      // Scheduled alongside, not after: with no delay columns carried the delay carries over.
      schedule(action.nextAction(), delayMs);
    }
  }

  /** The tags of every listed instance, finished ones included. */
  public long tags() {
    long tags = 0;
    for (ActionInstance instance : running) {
      tags |= instance.getTags();
    }
    return tags;
  }

  /** The listed instances, in list order. */
  public List<ActionInstance> running() {
    return List.copyOf(running);
  }

  @Override
  public void pendingPass(int phase) {
    passPhase = phase;
    try {
      int i = 0;
      while (i < pending.size()) {
        Entry entry = pending.get(i);
        int wanted = entry.action.phase();
        if (entry.ticks <= 0 && (wanted == BattleAction.ANY_PHASE || wanted == phase)) {
          removeBySwap(pending, i);
          start(entry.action, phase);
        } else {
          i++;
        }
      }
    } finally {
      passPhase = 0;
    }
  }

  @Override
  public void runPass(int tick) {
    int i = 0;
    while (i < running.size()) {
      ActionInstance instance = running.get(i);
      if (instance.isFinished()) {
        removeBySwap(running, i);
        listener.removed(instance);
        continue;
      }
      instance.update(this);
      if (instance.isFinished()) {
        listener.finished(instance);
      }
      i++;
    }
  }

  @Override
  public void endOfTick() {
    // There is no floor: a held entry keeps counting down past zero.
    for (Entry entry : pending) {
      entry.ticks--;
    }
  }

  private void start(BattleAction action, int phase) {
    ActionInstance instance = action.start(this);
    listener.started(action, phase);
    if (instance != null) {
      running.add(instance);
    }
  }

  /** Removes an element by moving the last one into its place. */
  private static <T> void removeBySwap(List<T> list, int index) {
    int last = list.size() - 1;
    list.set(index, list.get(last));
    list.remove(last);
  }
}
