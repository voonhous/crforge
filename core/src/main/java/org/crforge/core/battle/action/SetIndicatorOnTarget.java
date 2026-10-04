package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import org.crforge.core.battle.filter.FilterSubject;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.filter.ObjectCensus;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that keeps a marked target for its owner, as the Mega Minion hero's mark does: a run
 * that lasts and, while it has no target, searches the battle for one through its target resolver
 * and sets the tags of having none.
 *
 * <p>Each step of the run, with no target:
 *
 * <ol>
 *   <li>With PauseIfInCooldown, a run whose owner's champion slot has cooldown left does not search
 *       (refused here, below).
 *   <li>Otherwise it searches when its counter is -1 (its first step: the run starts it at -1) or
 *       has reached DelayBeforeSearchForNextTarget. The search offers every live object of the
 *       owner's battle, in id order and the owner among them, to the resolver's filter for the
 *       owner's team: a Global shape collects them all, wherever they stand.
 *   <li>Still without a target it sets the tags of having none, clears the tags of having one, and
 *       adds 50 to its counter, a fixed step whatever the clock. The counter is reset only by a
 *       pick, so once the delay has passed without one it searches every step.
 * </ol>
 *
 * <p>A leave notice of the object it has marked would run its died action; without a target the
 * notice does nothing.
 *
 * <p>Refused rather than guessed, at the step that reaches them: a search whose filter lets an
 * object through - the pick by the resolver's strategies, the new target's action, the tags of
 * having a target and the reset of the counter, and with them the died action, are not modelled -
 * and a pause in the owner's ability cooldown.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the run without a target - the counter from -1, the search on the first step and"
            + " then once the delay has passed, the Global shape's census through the resolver's"
            + " filter for the owner's team, the two tag masks set and cleared each step, the fixed"
            + " 50 a step - and the leave notice doing nothing without a target; held by"
            + " hero_mega_minion. Refused: a search that finds a candidate (the strategies' pick,"
            + " the pick action, the tags of having a target, the died action) and a pause in the"
            + " owner's ability cooldown.")
public final class SetIndicatorOnTarget extends RowAction {

  /** Milliseconds a step without a target adds to the counter: a constant, not the clock's step. */
  private static final int STEP_MS = 50;

  /** What the run asks of the battle about its owner. */
  public interface Host {

    /** The owner's battle's objects, with the owner's team and row name. */
    ObjectCensus census();

    /**
     * The cooldown left on the champion slot that follows the owner, in milliseconds; 0 with no
     * slot following it.
     */
    int abilityCooldownMs();

    /** The owner's state. */
    int state();
  }

  /**
   * The row's columns besides the shared ones.
   *
   * @param resolver the target resolver's row name
   * @param filter the resolver's filter
   * @param strategies the resolver's strategies, as the data names them
   * @param onPickNewTarget the name of the row run on a new target, or null
   * @param onTargetDied the name of the row run as the target leaves, or null
   * @param tagsWithoutTarget the tags set while there is no target
   * @param tagsWithTarget the tags set while there is a target
   * @param pauseIfInCooldown true when the search waits out the owner's ability cooldown
   * @param delayBeforeSearchMs the milliseconds between searches without a target
   */
  @Builder
  public record Columns(
      String resolver,
      GameObjectFilter filter,
      List<String> strategies,
      String onPickNewTarget,
      String onTargetDied,
      long tagsWithoutTarget,
      long tagsWithTarget,
      boolean pauseIfInCooldown,
      int delayBeforeSearchMs) {}

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public SetIndicatorOnTarget(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  /** The row's own columns. */
  public Columns columns() {
    return columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(this, holder.getOwner().markHost(this));
  }

  /** One run of the mark. */
  public static final class Run extends ActionInstance {

    private final SetIndicatorOnTarget mark;
    private final Host host;

    /** Milliseconds since the last search, or -1 before the first. */
    private int counterMs = -1;

    private Run(SetIndicatorOnTarget mark, Host host) {
      super(mark);
      this.mark = mark;
      this.host = host;
    }

    /** The marked object's id, or -1 for none: a run here never holds one. */
    public int targetId() {
      return -1;
    }

    @Override
    protected void update(ActionHolder holder) {
      Columns columns = mark.columns;
      if (columns.pauseIfInCooldown() && host.abilityCooldownMs() >= 1) {
        throw new UnsupportedOperationException(
            mark.name()
                + " waits out its owner's ability cooldown, which is not modelled (the pause's"
                + " lift for an active champion is untraced)");
      }
      if (counterMs == -1 || counterMs >= columns.delayBeforeSearchMs()) {
        search(columns);
      }
      addTags(columns.tagsWithoutTarget());
      clearTags(columns.tagsWithTarget());
      counterMs += STEP_MS;
    }

    /** The resolver's search, refused once its filter lets anything through. */
    private void search(Columns columns) {
      ObjectCensus census = host.census();
      List<String> found =
          census.objects().stream()
              .filter(object -> columns.filter().matches(object, census.team(), census.rowName()))
              .map(FilterSubject::rowName)
              .toList();
      if (!found.isEmpty()) {
        throw new UnsupportedOperationException(
            mark.name()
                + " finds "
                + found
                + " through "
                + columns.resolver()
                + ": the pick by "
                + columns.strategies()
                + ", the mark and "
                + columns.onPickNewTarget()
                + " are not modelled");
      }
    }
  }
}
