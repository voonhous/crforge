package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import lombok.Getter;
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
 * <p><b>Scheduling.</b> A delay is in milliseconds; {@link #OWN_DELAY} stands for the row's own.
 * Whether the action starts now is decided on the milliseconds, and the queue keeps the delay as
 * whole ticks, so a delay under one tick is still queued, with no ticks left, and starts at the
 * next pending pass. A delay of zero or less starts the action at once only when the schedule asks
 * for it or a pending pass is in progress; anywhere else - at placement, from a component pass,
 * from the run pass or a post-hook - it is queued with no ticks left. In a battle the pending
 * passes run over every entity together, so an action scheduled onto another entity from inside a
 * pending pass starts at once too. Either way the row is told it was scheduled, and a next action
 * that does not wait is scheduled alongside, its delay the one carried in less the row's own plus
 * its own, and never below zero.
 *
 * <p><b>Starting.</b> A singleton row with a run already listed re-triggers that run and starts
 * nothing. Otherwise a start gate that answers 0 ends the start; the action then does what it does,
 * its run, if it lasts, is listed carrying the row's tags, and a next action that waits is
 * scheduled with its own delay.
 *
 * <p><b>The passes.</b> A pending pass takes each due entry of its phase whose pause gate does not
 * hold it, and a held entry keeps counting down past zero. The run pass removes an instance that
 * finished in an earlier step; otherwise it asks the stop gate, and a run it stops is removed in
 * the same pass; otherwise it steps the run. An instance that finishes in its own step therefore
 * stays listed, and keeps setting its tags, until the next run pass.
 *
 * <p>Two orders are the standard game's and are kept. A pending pass takes the entry it finds and
 * moves the last entry into its place, then looks at that place again, so four due entries {@code a
 * b c d} start as {@code a d c b}. The run pass removes an instance the same way.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the three pending passes and where an action with no delay starts - inside any"
            + " entity's pending pass, as the battle's one flag says - delays in"
            + " milliseconds queued as whole ticks, the row's own delay standing in for none, the"
            + " swap-with-last order of a pending pass, the pause, start and stop gates, the"
            + " singleton re-trigger, the next action scheduled after the run or alongside with"
            + " the carried delay, the row's tags on the run, the run pass removing a finished run"
            + " at its next pass and a stopped one at once, the tags of every listed run folded in,"
            + " and the delay taken off in the end pass. Held by the recorded runtime cases. Not"
            + " modelled: the row hook asked when an action is scheduled and when its run starts,"
            + " the target an entry carries, and the notice to an action's instigator. The cause"
            + " an entry carries is the holder of the entity that caused it.")
public class ActionHolder implements EntityActions {

  /** The delay that stands for the row's own. */
  public static final int OWN_DELAY = -1;

  /** Milliseconds one tick takes off a queued delay. */
  private static final int TICK_MS = 50;

  /** What an observer of the holder is told as its actions start, stop, finish and leave. */
  public interface Listener {

    /** An action started, in the pending pass of the given phase, or 0 outside every pass. */
    default void started(BattleAction action, int phase) {}

    /** An instance finished during its step in the run pass. */
    default void finished(ActionInstance instance) {}

    /** The run pass stopped an instance whose stop gate held. */
    default void forceStopped(ActionInstance instance) {}

    /** The run pass removed an instance that had finished or was stopped. */
    default void removed(ActionInstance instance) {}
  }

  /**
   * One queued action as it stands.
   *
   * @param action the action
   * @param ticks the ticks left before it is due, below zero for an entry held past its due tick
   */
  public record Queued(BattleAction action, int ticks) {}

  /** One queued action, what caused it and the ticks left before it is due. */
  private static final class Entry {
    private final BattleAction action;
    private final ActionHolder instigator;
    private int ticks;

    private Entry(BattleAction action, ActionHolder instigator, int ticks) {
      this.action = action;
      this.instigator = instigator;
      this.ticks = ticks;
    }
  }

  private final List<Entry> pending = new ArrayList<>();
  private final List<ActionInstance> running = new ArrayList<>();

  /** The phase of the pending pass in progress, or 0 outside every pending pass. */
  private int passPhase;

  /**
   * Whether a pending pass is in progress, as the battle answers it for every entity at once, or
   * null for a holder outside a battle, which answers for its own passes alone.
   */
  private final BooleanSupplier battleInPendingPass;

  /** The tick of the last run pass. */
  @Getter private int lastTick;

  /** The entity the holder belongs to, which the leaves that act on their owner act on. */
  @Getter private final ActionOwner owner;

  /** A holder with no owner: its actions act on nothing but each other. */
  public ActionHolder() {
    this(null);
  }

  /**
   * A holder that belongs to an entity.
   *
   * @param owner the entity, or null for none
   */
  public ActionHolder(ActionOwner owner) {
    this(owner, null);
  }

  /**
   * A holder that belongs to an entity of a battle, whose pending passes the battle runs over every
   * entity together: while any of them runs, an action with no delay scheduled here starts at once.
   *
   * @param owner the entity, or null for none
   * @param battleInPendingPass whether the battle is inside a pending pass, or null for a holder
   *     that answers for its own passes
   */
  public ActionHolder(ActionOwner owner, BooleanSupplier battleInPendingPass) {
    this.owner = owner;
    this.battleInPendingPass = battleInPendingPass;
  }

  @Setter private Listener listener = new Listener() {};

  /**
   * Schedules an action as the battle does outside a request to start it now: with no delay it
   * starts at once only inside a pending pass.
   *
   * @param action the action
   * @param delayMs the delay in milliseconds, or {@link #OWN_DELAY} for the row's own
   */
  public void schedule(BattleAction action, int delayMs) {
    schedule(action, delayMs, false);
  }

  /**
   * Schedules an action, and the next action alongside it when that one does not wait.
   *
   * @param action the action
   * @param delayMs the delay in milliseconds, or {@link #OWN_DELAY} for the row's own
   * @param immediate true to start the action at once when its delay is zero or less, wherever the
   *     schedule is made
   */
  public void schedule(BattleAction action, int delayMs, boolean immediate) {
    schedule(action, delayMs, immediate, null);
  }

  /**
   * Schedules an action that an entity caused, and the next action alongside it when that one does
   * not wait; the cause goes with both.
   *
   * @param action the action
   * @param delayMs the delay in milliseconds, or {@link #OWN_DELAY} for the row's own
   * @param immediate true to start the action at once when its delay is zero or less
   * @param instigator the holder of the entity that caused it, or null for none
   */
  public void schedule(
      BattleAction action, int delayMs, boolean immediate, ActionHolder instigator) {
    int delay = delayMs == OWN_DELAY ? action.delayMs() : delayMs;
    if (delay <= 0 && (immediate || inPendingPass())) {
      start(action, instigator);
    } else {
      pending.add(new Entry(action, instigator, Math.max(delay, 0) / TICK_MS));
    }
    action.scheduled(this, delay, immediate, instigator);
    BattleAction next = action.nextAction();
    if (next != null && !action.nextActionWait()) {
      schedule(next, Math.max(delay - action.delayMs() + next.delayMs(), 0), immediate, instigator);
    }
  }

  /**
   * Starts an action now: re-triggers a singleton's listed run, or passes the start gate, does what
   * the action does, lists its run and chains a next action that waits.
   *
   * @param action the action
   */
  public void start(BattleAction action) {
    start(action, null);
  }

  /**
   * Starts an action an entity caused, as {@link #start(BattleAction)} does; a next action that
   * waits goes with the same cause.
   *
   * @param action the action
   * @param instigator the holder of the entity that caused it, or null for none
   */
  public void start(BattleAction action, ActionHolder instigator) {
    if (action.singleton()) {
      for (ActionInstance instance : running) {
        if (instance.getAction() == action) {
          instance.retrigger(this);
          return;
        }
      }
    }
    if (!holds(action.executeIf(), true)) {
      return;
    }
    ActionInstance instance = action.start(this, instigator);
    listener.started(action, passPhase);
    if (instance != null) {
      instance.addTags(action.tags());
      running.add(instance);
    }
    BattleAction next = action.nextAction();
    if (next != null && action.nextActionWait()) {
      schedule(next, OWN_DELAY, false, instigator);
    }
  }

  /** Whether a pending pass is in progress: the battle's, or this holder's own outside a battle. */
  private boolean inPendingPass() {
    return battleInPendingPass != null ? battleInPendingPass.getAsBoolean() : passPhase != 0;
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

  /** The queued actions, in queue order. */
  public List<Queued> queued() {
    return pending.stream().map(e -> new Queued(e.action, e.ticks)).toList();
  }

  /** What caused each queued action, in queue order; null for none. */
  public List<ActionHolder> queuedInstigators() {
    List<ActionHolder> out = new ArrayList<>();
    for (Entry entry : pending) {
      out.add(entry.instigator);
    }
    return out;
  }

  @Override
  public void pendingPass(int phase) {
    passPhase = phase;
    try {
      int i = 0;
      while (i < pending.size()) {
        Entry entry = pending.get(i);
        int wanted = entry.action.phase();
        if (entry.ticks <= 0
            && (wanted == BattleAction.ANY_PHASE || wanted == phase)
            && !holds(entry.action.pausedIf(), false)) {
          removeBySwap(pending, i);
          start(entry.action, entry.instigator);
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
    lastTick = tick;
    int i = 0;
    while (i < running.size()) {
      ActionInstance instance = running.get(i);
      if (instance.isFinished()) {
        removeBySwap(running, i);
        listener.removed(instance);
        continue;
      }
      if (holds(instance.getAction().forceStopIf(), false)) {
        instance.finish();
        listener.forceStopped(instance);
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

  /** The value of a gate as a truth, or the given answer for a row without the gate. */
  private static boolean holds(IntSupplier gate, boolean absent) {
    return gate == null ? absent : gate.getAsInt() != 0;
  }

  /** Removes an element by moving the last one into its place. */
  private static <T> void removeBySwap(List<T> list, int index) {
    int last = list.size() - 1;
    list.set(index, list.get(last));
    list.remove(last);
  }
}
