package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that runs another on the objects its target resolver finds, as the Ice Wizard hero's
 * ability looks for the enemy it has slowed.
 *
 * <p>When it starts, the resolver collects around the owner's position: a Global shape offers every
 * live object of the battle, in id order and the owner among them, to the resolver's filter, asked
 * by the owner. With Amount exactly 1 each of the resolver's strategies in turn narrows what passed
 * to its ties - a strategy that keeps nothing leaving the pool as it was, one that keeps a single
 * candidate ending the search - and the first left is the one object found.
 * RESOLVER_STRATEGY_CLOSEST_TARGET keeps the smallest squared distance from the owner's position,
 * RESOLVER_STRATEGY_FURTHEST_TARGET the largest, RESOLVER_STRATEGY_LOWEST_MAX_HP the lowest maximum
 * hit points plus maximum shield.
 *
 * <p>Action is then scheduled on the object found, built for it, with the owner as its cause and
 * the context the start carried; with RunActionsOnSelf it is scheduled on the owner instead, the
 * object its cause. When nothing is found, ActionToRunOnSelfIfNoObjectsFound is scheduled on the
 * owner, the owner its own cause, with the same context. Each is scheduled with its own delay and
 * not at once.
 *
 * <p>Refused rather than guessed: an Amount other than 1 for a row that runs an Action on what it
 * finds (the order of the many pick is not traced on this version), an Amount below 1, and any
 * other strategy. A resolver whose shape is not a Global one, the custom position expressions and
 * the ignored ids are refused as the row is built.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the perform's single path - the resolver asked at the owner's position, the"
            + " Global census through the filter asked by the owner, the strategies narrowing in"
            + " turn, the closest by squared distance - the action on the object found with the"
            + " owner its cause and the start's context, RunActionsOnSelf swapping the two, and"
            + " the self action when nothing is found; held by BattleRunOnResolvedTest and the"
            + " Ice Wizard hero's tap. A many pick with no action only asks whether anything"
            + " passed. Refused: a many pick that runs an action, an Amount below 1 and any other"
            + " strategy.")
public final class RunOnResolvedObjects extends RowAction {

  /** The strategy that keeps the smallest squared distance from the position asked. */
  public static final String CLOSEST_TARGET = "RESOLVER_STRATEGY_CLOSEST_TARGET";

  /** What the action asks of the battle about its owner. */
  public interface Host {

    /**
     * The battle's live objects the filter lets through, asked by the owner, in id order, the owner
     * among those asked.
     */
    List<SetIndicatorOnTarget.Candidate> candidates(GameObjectFilter filter);

    /** The owner's position along the width. */
    int x();

    /** The owner's position along the length. */
    int y();

    /**
     * Schedules a row on an object, built for it, with a cause and a context: its own delay, not at
     * once.
     *
     * @param target the object it runs on
     * @param action the row's name
     * @param cause the holder of its cause
     * @param context the context, or null for none
     */
    void schedule(
        SetIndicatorOnTarget.Candidate target,
        String action,
        ActionHolder cause,
        ActionContext context);

    /**
     * Schedules a row on the owner, built for it, with a cause and a context: its own delay, not at
     * once.
     *
     * @param action the row's name
     * @param cause the holder of its cause
     * @param context the context, or null for none
     */
    void scheduleOnOwner(String action, ActionHolder cause, ActionContext context);
  }

  /**
   * The row's columns besides the shared ones.
   *
   * @param resolver the target resolver's row name
   * @param filter the resolver's filter
   * @param strategies the resolver's strategies, as the data names them
   * @param amount how many objects it may run the action on
   * @param action the name of the row run on what it finds, or null for none
   * @param noObjectsAction the name of the row run on the owner when nothing is found, or null
   * @param runActionsOnSelf true to run the action on the owner, what it found its cause
   */
  @Builder
  public record Columns(
      String resolver,
      GameObjectFilter filter,
      List<String> strategies,
      int amount,
      String action,
      String noObjectsAction,
      boolean runActionsOnSelf) {}

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public RunOnResolvedObjects(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  /** The row's own columns. */
  public Columns columns() {
    return columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return start(holder, instigator, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    Host host = holder.getOwner().resolvedObjectsHost(this);
    if (columns.amount() < 1) {
      throw new UnsupportedOperationException(
          name() + " resolves " + columns.amount() + " objects, which is not modelled");
    }
    List<SetIndicatorOnTarget.Candidate> pool = host.candidates(columns.filter());
    if (columns.amount() != 1) {
      // The many pick takes up to Amount objects; with no action to run on them only whether
      // anything passed the filter matters, and any strategy keeps a non-empty pool non-empty.
      if (columns.action() != null) {
        throw new UnsupportedOperationException(
            name()
                + " runs "
                + columns.action()
                + " on up to "
                + columns.amount()
                + " objects, whose order is not modelled");
      }
      for (String strategy : columns.strategies()) {
        checkStrategy(strategy);
      }
      if (pool.isEmpty() && columns.noObjectsAction() != null) {
        host.scheduleOnOwner(columns.noObjectsAction(), holder, context);
      }
      return null;
    }
    SetIndicatorOnTarget.Candidate found = pick(host, pool);
    if (found == null) {
      if (columns.noObjectsAction() != null) {
        host.scheduleOnOwner(columns.noObjectsAction(), holder, context);
      }
      return null;
    }
    if (columns.action() != null) {
      if (columns.runActionsOnSelf()) {
        host.scheduleOnOwner(columns.action(), found.holder(), context);
      } else {
        host.schedule(found, columns.action(), holder, context);
      }
    }
    return null;
  }

  /** The single pick: the pool narrowed by each strategy in turn, its first, or null. */
  private SetIndicatorOnTarget.Candidate pick(
      Host host, List<SetIndicatorOnTarget.Candidate> pool) {
    if (pool.isEmpty()) {
      return null;
    }
    for (String strategy : columns.strategies()) {
      List<SetIndicatorOnTarget.Candidate> ties = ties(host, strategy, pool);
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

  /** Refuses a strategy not modelled. */
  private void checkStrategy(String strategy) {
    if (!strategy.equals(CLOSEST_TARGET)
        && !strategy.equals(SetIndicatorOnTarget.FURTHEST_TARGET)
        && !strategy.equals(SetIndicatorOnTarget.LOWEST_MAX_HP)) {
      throw new UnsupportedOperationException(
          name()
              + " resolves through "
              + columns.resolver()
              + " by "
              + strategy
              + ", which is not modelled");
    }
  }

  /** The candidates tied for a strategy's best, in their order. */
  private List<SetIndicatorOnTarget.Candidate> ties(
      Host host, String strategy, List<SetIndicatorOnTarget.Candidate> pool) {
    checkStrategy(strategy);
    List<Long> scores = new ArrayList<>();
    for (SetIndicatorOnTarget.Candidate candidate : pool) {
      long dx = (long) candidate.x() - host.x();
      long dy = (long) candidate.y() - host.y();
      scores.add(
          switch (strategy) {
            case CLOSEST_TARGET -> dx * dx + dy * dy;
            case SetIndicatorOnTarget.FURTHEST_TARGET -> -(dx * dx + dy * dy);
            default -> (long) candidate.maxHitPoints();
          });
    }
    long best = scores.stream().mapToLong(Long::longValue).min().orElseThrow();
    List<SetIndicatorOnTarget.Candidate> ties = new ArrayList<>();
    for (int i = 0; i < pool.size(); i++) {
      if (scores.get(i) == best) {
        ties.add(pool.get(i));
      }
    }
    return ties;
  }
}
