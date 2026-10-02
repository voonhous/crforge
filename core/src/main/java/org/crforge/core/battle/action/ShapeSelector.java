package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts on an area effect and, on ticks set from its start, runs an action on the
 * object in a circle around it that scores highest, as Vines does: each entry of its delays is due
 * on its own tick and picks one object for the action at the same index. It does nothing as it
 * starts but list its run, which records the battle tick and, for each delay, the tick it is due
 * on: the start plus the delay in whole steps.
 *
 * <p>Each step takes the entries due on exactly its tick, in order. The first of them queries the
 * circle once, at the area effect's point; an empty circle ends the step there. Each picks, among
 * the objects found that no entry has picked before, the one of the highest score - its hit points,
 * with its shield's for the mode that includes shields - starting from 0, an equal score going to
 * the lower id; with nobody left the entry picks nobody. The entry's action is scheduled on the
 * pick, the area effect as the cause, and the pick is remembered when the row picks each object
 * once. A step at which no entry is due later than its tick finishes the run.
 *
 * <p>Refused as the row is built: waiting for a target, pauses, a finishing action, the actions on
 * the area effect itself, a mode other than the two by current hit points, fewer actions than
 * delays, a singleton, a next action and tags, none of which a reference holds; and a row without a
 * filter, or whose shape is not a circle. As it starts: an owner other than an area effect.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the due ticks, the one query a step, the pick by hit points and"
            + " shield with ties to the lower id, each object picked once, an entry with nobody"
            + " left, the schedules and the finish; held by vines_group, three picks among four,"
            + " and vines_tower, the third entry picking nobody. An empty circle, which ends the"
            + " step before the finish, is held by BattleShapeSelectorTest alone. Refused: waiting,"
            + " pauses, a finishing action, the actions on the owner, the modes by maximum or"
            + " distance, a singleton, a next action, tags, a missing filter and a shape other"
            + " than a circle.")
public final class ShapeSelector extends RowAction {

  /** The mode that scores an object by its hit points. */
  public static final int HIGHEST_CURRENT_HP = 0;

  /** The mode that scores an object by its hit points and its shield's. */
  public static final int HIGHEST_CURRENT_HP_INCLUDE_SHIELDS = 2;

  /** The step a delay is counted in, in milliseconds. */
  private static final int STEP_MS = 50;

  /**
   * The row's own columns.
   *
   * @param oncePerTarget true when an object picked once is never picked again
   * @param targetSelectionMode how an object is scored
   * @param targetFilter the filter its query asks
   * @param shapeRadius the radius of its circle
   * @param delaysMs when each entry is due after the start, in order
   * @param actions the row of the action each entry runs on its pick, by index, built for the pick
   *     as it is scheduled
   */
  @Builder
  public record Columns(
      boolean oncePerTarget,
      int targetSelectionMode,
      GameObjectFilter targetFilter,
      int shapeRadius,
      List<Integer> delaysMs,
      List<String> actions) {

    public Columns {
      delaysMs = List.copyOf(delaysMs);
      actions = List.copyOf(actions);
    }
  }

  /**
   * What one step did: what its query found, each object's score, each entry's pick, the entries
   * with nobody to pick, whether the circle was empty and whether the run finished, and the due
   * ticks and picks before and after.
   *
   * @param found the ids the query found, in its order; empty when it made none
   * @param scores each scored object's id and score, in scoring order
   * @param chosen each entry's index and its pick's id
   * @param none the indices of the entries that picked nobody
   * @param empty true when the circle held nobody
   * @param finished true when the step finished the run
   * @param dueBefore the due ticks as the step began
   * @param hitBefore the ids picked before the step
   * @param hit the ids picked after it
   */
  public record Step(
      List<Integer> found,
      List<int[]> scores,
      List<int[]> chosen,
      List<Integer> none,
      boolean empty,
      boolean finished,
      List<Integer> dueBefore,
      List<Integer> hitBefore,
      List<Integer> hit) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public ShapeSelector(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    ShapeSelectorHost host = holder.getOwner().shapeSelectorHost();
    Run run = new Run(host);
    int start = host.tick();
    for (int delay : columns.delaysMs()) {
      run.due.add(start + delay / STEP_MS);
    }
    host.selectorStarted(this, holder.passPhase(), List.copyOf(run.due));
    return run;
  }

  /** One run: the due ticks and the ids picked so far. */
  private final class Run extends ActionInstance {

    private final ShapeSelectorHost host;
    private final List<Integer> due = new ArrayList<>();
    private final List<Integer> hit = new ArrayList<>();

    private Run(ShapeSelectorHost host) {
      super(ShapeSelector.this);
      this.host = host;
    }

    @Override
    protected void update(ActionHolder holder) {
      List<Integer> dueBefore = List.copyOf(due);
      List<Integer> hitBefore = List.copyOf(hit);
      List<Integer> found = null;
      List<int[]> scores = new ArrayList<>();
      List<int[]> chosen = new ArrayList<>();
      List<Integer> none = new ArrayList<>();
      int now = host.tick();
      // The latest due tick other than this one.
      int later = 0;
      for (int i = 0; i < due.size(); i++) {
        int tick = due.get(i);
        if (tick != now) {
          later = Math.max(later, tick);
          continue;
        }
        if (found == null) {
          found = host.collect(columns.shapeRadius(), columns.targetFilter());
          if (found.isEmpty()) {
            // An empty circle ends the step before the finish's test.
            host.selectorStepped(
                ShapeSelector.this,
                new Step(
                    found, scores, chosen, none, true, false, dueBefore, hitBefore, hitBefore));
            return;
          }
        }
        Integer best = choose(found, scores);
        if (best == null) {
          none.add(i);
          continue;
        }
        chosen.add(new int[] {i, best});
        host.schedule(best, columns.actions().get(i));
        if (columns.oncePerTarget()) {
          hit.add(best);
        }
      }
      boolean finishing = due.isEmpty() || later < now;
      if (finishing) {
        finish();
      }
      if (found != null || finishing) {
        host.selectorStepped(
            ShapeSelector.this,
            new Step(
                found == null ? List.of() : found,
                scores,
                chosen,
                none,
                false,
                finishing,
                dueBefore,
                hitBefore,
                List.copyOf(hit)));
      }
    }

    /**
     * The object of the highest score not picked before: the scores start from 0, a higher one
     * replaces the pick, and an equal one goes to the lower id, or is taken with nothing picked.
     */
    private Integer choose(List<Integer> found, List<int[]> scores) {
      Integer best = null;
      int bestScore = 0;
      for (int id : found) {
        if (hit.contains(id)) {
          continue;
        }
        int score = host.score(id, columns.targetSelectionMode());
        scores.add(new int[] {id, score});
        if (score > bestScore) {
          best = id;
          bestScore = score;
        } else if (score == bestScore && (best == null || best >= id)) {
          best = id;
        }
      }
      return best;
    }
  }
}
