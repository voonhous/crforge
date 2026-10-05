package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import lombok.Builder;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that keeps a marked target for its owner, as the Mega Minion hero's mark does: a run
 * that lasts, searches the battle for a target through its target resolver while it has none, and
 * keeps the one it finds while it lives.
 *
 * <p>Each step of the run:
 *
 * <ol>
 *   <li>The pin is cleared. A run that carries a context then asks its pin, PinnedActiveExpression,
 *       with it; one that holds pins the run for the step, at the point PinnedPositionXExpression
 *       and PinnedPositionYExpression answer with the same context. A pinned run does not search.
 *       Nothing in the run reads the pinned point.
 *   <li>With PauseIfInCooldown, a run without a target whose owner's champion slot has cooldown
 *       left would not search (refused here, below, unless the run is pinned, which keeps it from
 *       searching either way). With a target the pause changes nothing: the run keeps the target
 *       either way.
 *   <li>Without a target, and not pinned, it searches when its counter is -1 (its first step: the
 *       run starts it at -1) or has reached DelayBeforeSearchForNextTarget. The search offers every
 *       live object of the owner's battle, in id order and the owner among them, to the resolver's
 *       filter for the owner's team: a Global shape collects them all, wherever they stand. Each of
 *       the resolver's strategies in turn narrows what passed to its ties, a strategy that keeps
 *       nothing leaving them as they were and one that keeps a single candidate ending the search,
 *       and the first left is the target: RESOLVER_STRATEGY_LOWEST_MAX_HP keeps the lowest maximum
 *       hit points plus maximum shield, RESOLVER_STRATEGY_FURTHEST_TARGET the largest squared
 *       distance from the owner.
 *   <li>With a target it sets the tags of having one and clears the tags of having none. A target
 *       other than the last step's is a new one: OnPickNewTargetAction is scheduled on the owner,
 *       the target its cause, and the counter is set to 0. The run never searches again while it
 *       holds a target: it has no range or validity test.
 *   <li>Without one it sets the tags of having none, clears the tags of having one, and adds 50 to
 *       its counter, a fixed step whatever the clock. The counter is reset only by a pick, so once
 *       the delay has passed without one it searches every step.
 * </ol>
 *
 * <p>A leave notice of the object it has marked would run its died action on the owner and drop the
 * target; a notice of any other object does nothing.
 *
 * <p>Refused rather than guessed, at the step that reaches them: a pause in the owner's ability
 * cooldown without a target and without a pin, an object other than a character or a building let
 * through by the filter, a strategy other than the two above, and the marked object leaving (its
 * died action is not modelled).
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the run without a target - the counter from -1, the search on the first step and"
            + " then once the delay has passed, the Global shape's census through the resolver's"
            + " filter for the owner's team, the two tag masks set and cleared each step, the fixed"
            + " 50 a step - and the leave notice doing nothing without a target; held by"
            + " hero_mega_minion. The pick by the lowest maximum hit points then the furthest, the"
            + " tags of having a target, the new target's action on the owner with the target as"
            + " its cause and the counter's reset, the sticky target and the pause doing nothing"
            + " with one; held by ability_hero_mega_minion_vs_musketeer. Refused: a pause without a"
            + " target, any other strategy, an object other than a character or building found,"
            + " and the marked object leaving. The pin cleared and asked first each step with the"
            + " run's context, only by a run that has one, and a pin that holds keeping the run"
            + " from searching, held by BattleMarkPinTest and BattleMegaMinionReturnTest. The"
            + " pinned point is recorded; no reader of it is traced.")
public final class SetIndicatorOnTarget extends RowAction {

  /** Milliseconds a step without a target adds to the counter: a constant, not the clock's step. */
  private static final int STEP_MS = 50;

  /** The strategy that keeps the lowest maximum hit points plus maximum shield. */
  public static final String LOWEST_MAX_HP = "RESOLVER_STRATEGY_LOWEST_MAX_HP";

  /** The strategy that keeps the largest squared distance from the owner. */
  public static final String FURTHEST_TARGET = "RESOLVER_STRATEGY_FURTHEST_TARGET";

  /** An object the search can mark, read as the run needs it, live. */
  public interface Candidate {

    /** The object's id. */
    int id();

    /** The object's row name. */
    String rowName();

    /** Its maximum hit points plus its maximum shield, as the lowest-maximum strategy reads it. */
    int maxHitPoints();

    /** Its current hit points plus its current shield, as the highest-current strategy reads it. */
    default int currentHitPoints() {
      throw new UnsupportedOperationException(
          "the current hit points of " + rowName() + " are not read by its resolver");
    }

    /** Its position along the width. */
    int x();

    /** Its position along the length. */
    int y();

    /** Its action holder, the cause of the actions the mark and its hand-over run. */
    ActionHolder holder();
  }

  /** What the run asks of the battle about its owner. */
  public interface Host {

    /**
     * The battle's live objects the filter lets through for the owner's team and row, in id order,
     * the owner among those asked.
     */
    List<Candidate> candidates(GameObjectFilter filter);

    /** The owner's position along the width. */
    int x();

    /** The owner's position along the length. */
    int y();

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
   * @param onPickNewTarget the action run on the owner for a new target, the target its cause, or
   *     null
   * @param onTargetDied the name of the row run as the target leaves, or null
   * @param tagsWithoutTarget the tags set while there is no target
   * @param tagsWithTarget the tags set while there is a target
   * @param pauseIfInCooldown true when the search waits out the owner's ability cooldown
   * @param delayBeforeSearchMs the milliseconds between searches without a target
   * @param pinnedActive the pin, asked each step with the run's context, or null for none
   * @param pinnedX the pinned point along the width, asked as the pin holds, or null for 0
   * @param pinnedY the pinned point along the length, asked as the pin holds, or null for 0
   */
  @Builder
  public record Columns(
      String resolver,
      GameObjectFilter filter,
      List<String> strategies,
      BattleAction onPickNewTarget,
      String onTargetDied,
      long tagsWithoutTarget,
      long tagsWithTarget,
      boolean pauseIfInCooldown,
      int delayBeforeSearchMs,
      IntSupplier pinnedActive,
      IntSupplier pinnedX,
      IntSupplier pinnedY) {}

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

    /** The marked object, or null for none. */
    private Candidate target;

    /** True while the pin holds, asked anew each step. */
    private boolean pinned;

    /** The pinned point along the width and the length, as the pin last held. */
    private int pinnedX;

    private int pinnedY;

    private Run(SetIndicatorOnTarget mark, Host host) {
      super(mark);
      this.mark = mark;
      this.host = host;
    }

    /** The marked object, or null for none. */
    public Candidate target() {
      return target;
    }

    /** True when the pin held on the last step. */
    public boolean pinned() {
      return pinned;
    }

    /** The pinned point along the width, as the pin last held. */
    public int pinnedX() {
      return pinnedX;
    }

    /** The pinned point along the length, as the pin last held. */
    public int pinnedY() {
      return pinnedY;
    }

    @Override
    protected void update(ActionHolder holder) {
      Columns columns = mark.columns;
      int previous = target == null ? -1 : target.id();
      // The pin, cleared and then asked each step, with the run's context, by a run that has one.
      // A pin that holds records its point, which nothing in the run reads, and keeps the run
      // from searching this step.
      pinned = false;
      if (columns.pinnedActive() != null
          && context() != null
          && columns.pinnedActive().getAsInt() != 0) {
        pinned = true;
        pinnedX = columns.pinnedX() == null ? 0 : columns.pinnedX().getAsInt();
        pinnedY = columns.pinnedY() == null ? 0 : columns.pinnedY().getAsInt();
      }
      // The pause only stops a search: with a target, or pinned, the run does not search either
      // way.
      if (target == null
          && !pinned
          && columns.pauseIfInCooldown()
          && host.abilityCooldownMs() >= 1) {
        throw new UnsupportedOperationException(
            mark.name()
                + " waits out its owner's ability cooldown, which is not modelled (the pause's"
                + " lift for an active champion is untraced)");
      }
      if (target == null
          && !pinned
          && (counterMs == -1 || counterMs >= columns.delayBeforeSearchMs())) {
        target = search(columns);
      }
      if (target == null) {
        addTags(columns.tagsWithoutTarget());
        clearTags(columns.tagsWithTarget());
        counterMs += STEP_MS;
        return;
      }
      addTags(columns.tagsWithTarget());
      clearTags(columns.tagsWithoutTarget());
      if (target.id() != previous) {
        if (columns.onPickNewTarget() != null) {
          holder.schedule(
              columns.onPickNewTarget(), ActionHolder.OWN_DELAY, false, target.holder());
          counterMs = 0;
          // The action may have dropped the target (its leave notice): then the step counts.
          if (target == null) {
            counterMs += STEP_MS;
          }
        } else {
          counterMs = 0;
        }
      }
    }

    @Override
    protected void objectLeft(int leftId) {
      if (target != null && target.id() == leftId) {
        throw new UnsupportedOperationException(
            mark.name()
                + "'s target "
                + target.rowName()
                + " leaves, whose died action "
                + mark.columns.onTargetDied()
                + " is not modelled");
      }
    }

    /** The resolver's search: the candidates the filter lets through, narrowed by each strategy. */
    private Candidate search(Columns columns) {
      List<Candidate> pool = host.candidates(columns.filter());
      if (pool.isEmpty()) {
        return null;
      }
      for (String strategy : columns.strategies()) {
        List<Candidate> ties = ties(strategy, pool);
        // A strategy that keeps nothing leaves the pool as it was; one that keeps a single
        // candidate ends the search, the strategies after it not asked.
        if (!ties.isEmpty()) {
          pool = ties;
          if (pool.size() == 1) {
            break;
          }
        }
      }
      return pool.get(0);
    }

    /** The candidates tied for a strategy's best, in their order. */
    private List<Candidate> ties(String strategy, List<Candidate> pool) {
      List<Long> scores = new ArrayList<>();
      for (Candidate candidate : pool) {
        scores.add(
            switch (strategy) {
              case LOWEST_MAX_HP -> (long) candidate.maxHitPoints();
              case FURTHEST_TARGET -> {
                long dx = (long) candidate.x() - host.x();
                long dy = (long) candidate.y() - host.y();
                yield -(dx * dx + dy * dy);
              }
              default ->
                  throw new UnsupportedOperationException(
                      mark.name()
                          + " resolves through "
                          + mark.columns.resolver()
                          + " by "
                          + strategy
                          + ", which is not modelled");
            });
      }
      long best = scores.stream().mapToLong(Long::longValue).min().orElseThrow();
      List<Candidate> ties = new ArrayList<>();
      for (int i = 0; i < pool.size(); i++) {
        if (scores.get(i) == best) {
          ties.add(pool.get(i));
        }
      }
      return ties;
    }
  }
}
