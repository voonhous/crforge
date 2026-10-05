package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that hides the object it runs on, as the evolved Snowball hides the units it carries:
 * every step of its run sets the hidden tag on the run, which the next pre-hook folds into the
 * object's tag word, so that from then on no attacker takes it, the damage entry refuses it and a
 * filter that drops hidden objects drops it. A run of the row while one is listed only re-triggers
 * it, which changes nothing.
 *
 * <p>The run keeps the id of what caused it, the hider. With a duration of at least 1 the run
 * finishes once its time has reached it, its time growing by 50 each step; with none it lasts. A
 * row that stops when the hider dies, as a row that leaves it empty does, finishes as the hider
 * leaves the battle. A finished run keeps its tag until the next run pass removes it.
 *
 * <p>Refused as it starts: a run with no cause.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the hidden tag set every step, the hider's id at the start, the"
            + " duration's end and the end as the hider leaves; held by"
            + " firecracker_snowball_goblins, where four captured Goblins are hidden until the"
            + " snowball leaves, and the hidden answer it gives by BattleSnowballEvoTest. Held by"
            + " no run: a duration. Refused: a run with no cause.")
public final class Hide extends RowAction {

  /** The step every timer takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /** How long the run lasts; below 1 it lasts until it is stopped. */
  @Getter private final int durationMs;

  /** True when the run finishes as its hider leaves the battle. */
  @Getter private final boolean stopWhenHiderDies;

  /** The bit of the HIDDEN tag the run sets, as the game tags table numbers it. */
  private final long hiddenTag;

  /**
   * @param row the row's shared columns
   * @param durationMs how long the run lasts; below 1 it lasts until it is stopped
   * @param stopWhenHiderDies true when the run finishes as its hider leaves the battle
   * @param hiddenTag the bit of the HIDDEN tag, as the game tags table numbers it
   */
  public Hide(ActionRow row, int durationMs, boolean stopWhenHiderDies, long hiddenTag) {
    super(row);
    this.durationMs = durationMs;
    this.stopWhenHiderDies = stopWhenHiderDies;
    this.hiddenTag = hiddenTag;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    throw new UnsupportedOperationException(name() + " runs with no cause, not modelled");
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator == null) {
      return start(holder);
    }
    return new Run(instigator.getOwner().actionId());
  }

  /** One run: its hider's id and how long it has hidden its object. */
  private final class Run extends ActionInstance {

    private final int hiderId;
    private int timeMs;

    private Run(int hiderId) {
      super(Hide.this);
      this.hiderId = hiderId;
    }

    @Override
    protected void update(ActionHolder holder) {
      addTags(hiddenTag);
      if (durationMs >= 1 && timeMs >= durationMs) {
        finish();
        return;
      }
      timeMs += STEP_MS;
    }

    @Override
    protected void objectLeft(int leftId) {
      if (stopWhenHiderDies && leftId == hiderId) {
        finish();
      }
    }
  }
}
