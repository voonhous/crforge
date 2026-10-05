package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.IntSupplier;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An attack chain, as the Valkyrie hero form's whirlwind runs: a run on its character that sends it
 * from one object its target resolver finds to the next, each reached once, until the chain is
 * complete, its time is up or nothing is left to reach.
 *
 * <p>The start picks the first object: the resolver's objects around the character in rank order,
 * at most 32, the first not reached before that is alive and that the character's targeting would
 * take. With one the chain begins on it: the movement switched on, the re-selection wait set to
 * 99999 ms, the reference set onto it through the setter's re-check, the chain's phase buff put on
 * the character for 99999 ms, and, the first time, the row's begin action scheduled on the
 * character. With none the run stays listed and looks again on each step.
 *
 * <p>Each step adds 50 ms to the run's time. A complete-if expression that holds schedules the
 * row's complete action and ends the chain; so does the time reaching the longest duration. With
 * the attack speed paused at zero the step does nothing more. Moving toward an object, a reference
 * gone or dead ends that link (a dead one counted as reached, the target-died action scheduled),
 * and a reference within attack range is reached: counted, the reach action scheduled, and the next
 * link begun. The next link drops the reference when the row resets it after a reach; once the
 * count of links reaches the row's, the complete action runs and the chain ends; else the next
 * object is picked and the reference set onto it, refreshed, with the movement switched on. With
 * nothing left the chain waits: the phase buff removed, the reference given up and the wait
 * cleared. Waiting, each step looks for an object again and, the row stopping the movement at the
 * target, raises NO_MOVE_ALLOW_ATTRACT for one step while the character's own reference is within
 * attack range.
 *
 * <p>The chain's end removes the phase buff, clears the re-selection wait and, when the row resets
 * the target after a reach, gives the reference up; the run's finish then schedules the row's
 * finishing action on the character, the character its own cause, once.
 *
 * <p>Refused as the row is built: a reach that attacks, a reach range of its own, the forget
 * columns, the default target as a fallback, a no-target action, a resolver of more than one
 * strategy, and the stop gate and the other shared columns the run does not read.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Read line for line: the start, the step, the reach, the next link, the wait and the end,"
            + " the pick over the ranked resolver list. Held by hero_valkyrie and"
            + " ValkyrieHeroAbilityTest. Refused: a reach that attacks, ReachRange, the forget"
            + " columns, CanUseDefaultTargetAsFallback, OnNoTargetFound, more than one strategy"
            + " and a stop gate.")
public final class AttackChain extends RowAction {

  /** The most objects the chain's pick ranks. */
  public static final int RANKED = 32;

  /**
   * The row's own columns.
   *
   * @param resolver the target resolver's row name
   * @param filter the resolver's filter
   * @param cone the resolver's Cone shape, a Circle one as a cone that keeps every angle, or null
   *     for a Global one
   * @param strategies the resolver's strategies, as the data names them
   * @param chainCount how many links the chain makes
   * @param resetTargetAfterReach true to give the reference up after each reach and at the end
   * @param onChainBegan the row scheduled as the first link begins, or null
   * @param onReachTarget the row scheduled as a live object is reached, or null
   * @param onTargetDied the row scheduled as the object moved toward is gone or dead, or null
   * @param onChainComplete the row scheduled as the chain completes, or null
   * @param onFinishedAction the row scheduled as the run finishes, or null
   * @param chainPhaseBuff the buff the character carries while it moves along the chain, or null
   * @param maxDurationMs the longest the chain lasts, below 1 for no limit
   * @param chainCompleteIf the expression that completes the chain, or null for none
   * @param pauseIfAttackSpeedZero true to pause the chain while the character's attack speed is 0
   * @param stopMovementWhenAtTarget true to hold the waiting character still at its reference
   */
  @Builder
  public record Columns(
      String resolver,
      GameObjectFilter filter,
      ConeShape cone,
      List<String> strategies,
      int chainCount,
      boolean resetTargetAfterReach,
      String onChainBegan,
      String onReachTarget,
      String onTargetDied,
      String onChainComplete,
      String onFinishedAction,
      String chainPhaseBuff,
      int maxDurationMs,
      IntSupplier chainCompleteIf,
      boolean pauseIfAttackSpeedZero,
      boolean stopMovementWhenAtTarget) {

    /** Copies the strategies. */
    public Columns {
      strategies = strategies == null ? List.of() : List.copyOf(strategies);
    }
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public AttackChain(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().attackChain(this, holder);
  }
}
