package org.crforge.core.battle;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One match as the simulation steps it: an integer millisecond clock, a tick counter, the entity
 * holder, the mode and the command queue.
 *
 * <p>One {@link #step()} is 50 ms of game time and runs, in order:
 *
 * <ol>
 *   <li>nothing at all when the mode says the match is over, not even the tick counter;
 *   <li>the clock advances by {@link #STEP_MS};
 *   <li>the mode update, and inside it the entity tick, which is handed the current tick;
 *   <li>the due commands, at the tail of the step and therefore after every entity visit;
 *   <li>the tick counter advances;
 *   <li>the due commands once more, against the advanced counter.
 * </ol>
 *
 * <p>Time is never a float here. Every duration in the simulation is whole milliseconds, stepped by
 * exactly 50, so a duration of {@code ms} lasts {@code ceil(ms / 50)} steps with no rounding drift.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: 50 ms per step as an integer, the entity tick before the commands, the tick"
            + " counter advancing after both, a finished match skipping the step and the counter,"
            + " and a second command pass after the counter. Not settled: which commands belong"
            + " to which of the two passes, the exact due rule for a late command, and the match"
            + " clock, which the mode will own.")
public class Battle {

  /** Game time one step advances, in milliseconds. */
  public static final int STEP_MS = 50;

  /** Steps per second of game time. */
  public static final int STEPS_PER_SECOND = 1000 / STEP_MS;

  @Getter private final EntityHolder holder;
  private final BattleMode mode;

  /** Queued commands in the order they were queued. */
  private final List<BattleCommand> commands = new ArrayList<>();

  /** Number of completed steps; the tick handed to the entity tick of the step in progress. */
  @Getter private int tick;

  /** Game time in milliseconds. */
  @Getter private int clockMs;

  public Battle(EntityHolder holder, BattleMode mode) {
    this.holder = holder;
    this.mode = mode;
  }

  /**
   * Queues a command. A command may not be stamped with a tick that has already completed: it would
   * have had to run in a step that is over.
   */
  public void queue(BattleCommand command) {
    checkArgument(
        command.tick() >= tick,
        () -> "command is due on tick " + command.tick() + " but the battle is on tick " + tick);
    commands.add(command);
  }

  /** Advances the battle by one 50 ms step. */
  public void step() {
    if (mode.isOver()) {
      return;
    }
    clockMs += STEP_MS;
    if (mode.update(this)) {
      holder.tick(tick);
    } else {
      holder.cleanup();
    }
    executeDueCommands();
    tick++;
    executeDueCommands();
  }

  /**
   * Runs every queued command that is due, in the order they were queued. A command queued by
   * another command during the pass is looked at in the same pass.
   */
  private void executeDueCommands() {
    // Indexed rather than iterated, so that a command may queue another while the pass runs: the
    // newcomer lands at the end of the list and the loop reaches it.
    int i = 0;
    while (i < commands.size()) {
      BattleCommand command = commands.get(i);
      if (command.tick() <= tick) {
        commands.remove(i);
        command.execute(this);
      } else {
        i++;
      }
    }
  }
}
