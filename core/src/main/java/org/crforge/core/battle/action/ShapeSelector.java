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
 * <p>A row that waits for a target, as the Giant hero form's slap does on the character itself,
 * takes every entry due at or before the step's tick instead of exactly on it, and drops an entry
 * once it picked: it finishes only when no entry is left, so it waits for as long as its circle
 * stays empty. A step while the owner's tag word meets the row's pause tags does nothing at all.
 * With a pick, the row's action on the owner by side runs first, on the owner, the pick as its
 * cause: of side 1 with the pick to its left (the owner's x greater) the left one, of side 0 the
 * right one, and the other way round otherwise; then the entry's action on the pick.
 *
 * <p>With a pick, the row's action on the owner whatever the side runs before the side action, on
 * the owner as well. Both take the pick as their cause, or the owner itself when the row says
 * ParentAsInstigatorForSelfActions; the entry's action on the pick always takes the owner. Every
 * action the row schedules carries the run's context. The Closest mode scores an object by the
 * largest int with the bits of its guarded squared distance from the owner flipped, so the nearest
 * wins and a tie goes, as any, to the lower id. The run's finish - the step's own, or a stop gate's
 * - schedules the row's finishing action on the owner, the owner its cause, with the run's context,
 * once.
 *
 * <p>Refused as the row is built: a longest wait, the modes by maximum hit points, fewer actions
 * than delays, a singleton and a next action, none of which a reference holds; and a row without a
 * filter, or whose shape is not a circle. As it starts: an owner other than an area effect or a
 * character. As the owner leaves: a run with a finishing action that has not finished, whose finish
 * on a leaving owner is not modelled.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the due ticks, the one query a step, the pick by hit points and"
            + " shield with ties to the lower id, each object picked once, an entry with nobody"
            + " left, the schedules and the finish; held by vines_group, three picks among four,"
            + " and vines_tower, the third entry picking nobody. An empty circle, which ends the"
            + " step before the finish, is held by BattleShapeSelectorTest alone, which runs the"
            + " slap selector written in Vines' form from Vines' area effect, as are the ties, the"
            + " shield, a row that picks again and an underground object. Waiting for a"
            + " target, the pause tags, the side actions on the owner and the row's tags on a"
            + " character, held by ability_hero_giant_slap. The action on the owner whatever the"
            + " side, the owner as the cause of the actions on itself, the Closest mode, the run's"
            + " context on every schedule and the finishing action, held by hero_balloon and"
            + " BattleBalloonHeroTest. Refused: a longest wait, the modes by maximum, a singleton,"
            + " a next action, a missing filter, a shape other than a circle and a finishing"
            + " action on an owner that leaves before the run finishes.")
public final class ShapeSelector extends RowAction {

  /** The mode that scores an object by its hit points. */
  public static final int HIGHEST_CURRENT_HP = 0;

  /** The mode that scores an object by its hit points and its shield's. */
  public static final int HIGHEST_CURRENT_HP_INCLUDE_SHIELDS = 2;

  /** The mode that scores an object by its nearness to the owner. */
  public static final int CLOSEST = 4;

  /** The step a delay is counted in, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The row's own columns; each action is built for the object it runs on as it is scheduled. */
  @Builder
  public record Columns(
      boolean oncePerTarget,
      int targetSelectionMode,
      GameObjectFilter targetFilter,
      int shapeRadius,
      List<Integer> delaysMs,
      List<String> actions,
      boolean waitForTarget,
      long pauseTags,
      String actionOnSelfLeft,
      String actionOnSelfRight,
      String actionOnSelf,
      boolean parentAsInstigatorForSelfActions,
      String onFinishedAction) {

    /**
     * @param oncePerTarget true when an object picked once is never picked again
     * @param targetSelectionMode how an object is scored
     * @param targetFilter the filter its query asks
     * @param shapeRadius the radius of its circle
     * @param delaysMs when each entry is due after the start, in order
     * @param actions the row of the action each entry runs on its pick, by index
     * @param waitForTarget true when an entry stays due from its tick on until it picks
     * @param pauseTags the tags that, in the owner's tag word, pause every step
     * @param actionOnSelfLeft the row run on the owner for a pick on its left side, or null
     * @param actionOnSelfRight the row run on the owner for a pick on its right side, or null
     * @param actionOnSelf the row run on the owner for every pick, before the side one, or null
     * @param parentAsInstigatorForSelfActions true when the actions on the owner take the owner as
     *     their cause in place of the pick
     * @param onFinishedAction the row run on the owner as the run finishes, or null
     */
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
    Run run = new Run(host, holder);
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

    /** The owner's holder, the cause of what the row runs on a pick and, under its flag, itself. */
    private final ActionHolder owner;

    private final List<Integer> due = new ArrayList<>();
    private final List<Integer> hit = new ArrayList<>();

    private Run(ShapeSelectorHost host, ActionHolder owner) {
      super(ShapeSelector.this);
      this.host = host;
      this.owner = owner;
    }

    /**
     * The finish, as the step ends the run or a stop gate holds: the first one schedules the
     * finishing action on the owner, the owner its cause, with the run's context.
     */
    @Override
    protected void finish() {
      if (isFinished()) {
        return;
      }
      super.finish();
      if (columns.onFinishedAction() != null) {
        host.scheduleOnOwner(columns.onFinishedAction(), owner, context());
      }
    }

    /**
     * The owner leaves: a finishing action of a run that has not finished would run on a leaving
     * owner, which is not modelled.
     */
    @Override
    protected void stop(ActionHolder holder) {
      if (!isFinished() && columns.onFinishedAction() != null) {
        throw new UnsupportedOperationException(
            name()
                + " is listed unfinished as its owner leaves, whose finishing action "
                + columns.onFinishedAction()
                + " is not modelled there");
      }
    }

    @Override
    protected void update(ActionHolder holder) {
      // A step while the owner carries a pause tag does nothing at all.
      if (columns.pauseTags() != 0 && (columns.pauseTags() & host.ownerTags()) != 0) {
        return;
      }
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
        if (columns.waitForTarget()) {
          // A waiting entry is due from its tick on.
          if (now < tick) {
            continue;
          }
        } else if (tick != now) {
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
        // The actions on the owner take the pick as their cause, or the owner under its flag.
        ActionHolder selfCause =
            columns.parentAsInstigatorForSelfActions() ? owner : host.holder(best);
        if (columns.actionOnSelf() != null) {
          host.scheduleOnOwner(columns.actionOnSelf(), selfCause, context());
        }
        String onSide = sideAction(best);
        if (onSide != null) {
          host.scheduleOnOwner(onSide, selfCause, context());
        }
        host.schedule(best, columns.actions().get(i), owner, context());
        if (columns.oncePerTarget()) {
          hit.add(best);
        }
        if (columns.waitForTarget()) {
          // The entry picked: swapped with the last one and dropped, the same index again.
          due.set(i, due.get(due.size() - 1));
          due.remove(due.size() - 1);
          i--;
        }
      }
      boolean finishing = due.isEmpty() || (!columns.waitForTarget() && later < now);
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
     * The row run on the owner for a pick by the side it lies on: for an owner of side 1 the left
     * row when the pick's x is below the owner's and the right row otherwise, for side 0 the other
     * way round, for any other side neither.
     */
    private String sideAction(int pick) {
      if (columns.actionOnSelfLeft() == null && columns.actionOnSelfRight() == null) {
        return null;
      }
      int side = host.ownerSide();
      boolean pickBelow = host.ownerX() > host.x(pick);
      if (pickBelow) {
        return side == 1
            ? columns.actionOnSelfLeft()
            : side == 0 ? columns.actionOnSelfRight() : null;
      }
      return side == 0
          ? columns.actionOnSelfLeft()
          : side == 1 ? columns.actionOnSelfRight() : null;
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
