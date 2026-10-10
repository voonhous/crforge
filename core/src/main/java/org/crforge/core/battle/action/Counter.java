/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts on its owner and counters a hit on it, as the Ronin's parry does.
 *
 * <p>Its run is listed as it starts, its elapsed time and its cooldown at 0. Each step adds 50 to
 * the elapsed time and finishes the run once it reaches a Duration of at least 1; otherwise a
 * running cooldown loses one step, never below 0.
 *
 * <p>Every hit that reaches the owner's damage entry, after the two sides' percentages and before
 * the bookkeeping, is told to the owner's runs from the last listed to the first. The run counters
 * it only when the hit is not a reflected one, has a source and the cooldown is not running, and
 * the owner's answers pass ({@link Host#counterTarget}). It then sets the cooldown, schedules
 * SelfAction on the owner, the hit's source its cause, and InstigatorAction on the source, the
 * owner its cause, both with the run's context and their rows' own delays; writes the hit's amount
 * under DamageKey into the context's main board; counts the counter against TriggerCount, finishing
 * the run once a count of at least 1 is reached; and scales the hit's amount by DefenseScalar
 * percent, truncated. An amount of 0 deals nothing.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the run's elapsed time and cooldown steps, the gates in their order, the"
            + " owner's attack cleared and its one-step no-pushback tag, the cooldown set, the two"
            + " actions with their causes and the run's context, the amount written before the"
            + " scale, the trigger count and the scale. Refused: CounterProjectiles, whose source"
            + " is the projectile's shooter, which no row sets.")
public final class Counter extends RowAction {

  /** The step every timer takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /**
   * The row's own columns.
   *
   * @param durationMs how long the run lasts; below 1 for as long as its owner lives
   * @param cooldownMs how long after a counter the next one waits
   * @param triggerCount the counters after which the run finishes; below 1 for no limit
   * @param selfAction scheduled on the owner as it counters, or null
   * @param instigatorAction scheduled on the hit's source as the owner counters, or null
   * @param damageKey the context key the hit's amount is written under, 0 for none
   * @param defenseScalar the percent of a countered hit's amount the owner still takes
   * @param includedFilter sources that pass it are countered whatever their range and layer, or
   *     null for none
   * @param attackerRangeThreshold the longest Range of a source that is countered
   * @param counterFlying true to counter a source in the air too
   * @param deployActive true to counter while the owner deploys, its targeting off
   */
  @Builder
  public record Columns(
      int durationMs,
      int cooldownMs,
      int triggerCount,
      BattleAction selfAction,
      BattleAction instigatorAction,
      int damageKey,
      int defenseScalar,
      GameObjectFilter includedFilter,
      int attackerRangeThreshold,
      boolean counterFlying,
      boolean deployActive) {}

  /** What the run asks of its owner as a hit reaches it. */
  public interface Host {

    /**
     * Whether the owner counters a hit from a source, and on what the instigator action runs: a
     * source that is a character, a building or a tower, passing the included filter or else not in
     * the air (unless the row counters flying sources) and of a Range no longer than the threshold;
     * an owner without the no-attack tag, whose targeting is on or that deploys under DeployActive,
     * and whose hit speed scale of a step is at least 1. A counter clears the owner's attack in
     * progress and raises its no-pushback tag for one step.
     *
     * @param columns the row's columns
     * @param source what the hit came from
     * @return the source's holder when the owner counters, else null
     */
    ActionHolder counterTarget(Columns columns, SpawnHost source);
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public Counter(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run();
  }

  /** The run: its elapsed time, its cooldown and its counters. */
  private final class Run extends ActionInstance {

    private int elapsedMs;

    private int cooldownMs;

    private int counters;

    private Run() {
      super(Counter.this);
    }

    @Override
    protected void update(ActionHolder holder) {
      if (columns.durationMs() >= 1) {
        elapsedMs += STEP_MS;
        if (elapsedMs >= columns.durationMs()) {
          finish();
          return;
        }
      }
      if (cooldownMs >= 1) {
        cooldownMs = Math.max(cooldownMs, STEP_MS) - STEP_MS;
      }
    }

    @Override
    protected void damageHeard(ActionHolder holder, DamageHeard hit) {
      if (hit.reflected() || hit.source() == null || cooldownMs >= 1) {
        return;
      }
      ActionHolder struck =
          holder.getOwner().counterHost(Counter.this).counterTarget(columns, hit.source());
      if (struck == null) {
        return;
      }
      cooldownMs = columns.cooldownMs();
      ActionContext context = context();
      if (columns.selfAction() != null) {
        holder.schedule(
            columns.selfAction(),
            ActionHolder.OWN_DELAY,
            false,
            hit.source().actionHolder(),
            context);
      }
      if (columns.instigatorAction() != null) {
        struck.schedule(columns.instigatorAction(), ActionHolder.OWN_DELAY, false, holder, context);
      }
      if (columns.damageKey() != 0 && context != null) {
        context.write(false, columns.damageKey(), hit.getAmount());
      }
      if (columns.triggerCount() >= 1) {
        counters++;
        if (counters >= columns.triggerCount()) {
          finish();
        }
      }
      hit.setAmount(hit.getAmount() * columns.defenseScalar() / 100);
    }
  }
}
