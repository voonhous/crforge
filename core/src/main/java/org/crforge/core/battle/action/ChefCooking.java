/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts and cooks for the Royal Chef's king tower: after a start delay it fills a
 * bar each step from what its side's princess-slot towers are doing, and each time the bar is full
 * it throws a projectile from one of those towers at a friendly troop, whose buff raises the
 * troop's level.
 *
 * <p>Each step, in this order:
 *
 * <ul>
 *   <li><b>The start delay.</b> While any of it is left it loses 50 and the step ends: the row's
 *       7000 holds the first 140 steps.
 *   <li><b>A throw waiting.</b> Its countdown loses 50; the step ends unless it was at 50 or less.
 *       A target that left in the meantime is chosen again, and with none the step ends. The
 *       projectile then goes from the chosen tower, if it is still found, and the bar loses what a
 *       full bar holds, keeping any overshoot.
 *   <li><b>Cooking.</b> The bar would gain the row's baseline plus, for each tower, its attacking
 *       or its idle contribution as the tower's buffs scale its hit speed, plus the destroyed
 *       contribution for each tower short of two. A full bar - the row's needed contribution times
 *       20 - instead tries a throw and gains nothing: with a target, a tower and that tower's
 *       attack letting it go, the run turns the tower to the target and starts the throw's
 *       countdown.
 * </ul>
 *
 * <p>The target is the live list's friendly object, by the row's filter, that carries the fewest of
 * the projectile's buff and then has the most hit points, the first of equals first; one that is in
 * state 6, has no hit points, at most the scaled minimum maximum or current hit points, or below
 * the minimum share of its maximum, is skipped. Shields count in both. There is no range. The tower
 * is one that is not attacking before one that is, then the nearer, the first of equals first.
 *
 * <p>The towers are the side's list of princess-slot towers, read every step. A destroyed tower
 * leaves that list in the cleanup that takes it out of the battle: from the next step it adds
 * nothing and is never chosen, the destroyed contribution counts for it, and with no tower left the
 * run ends when the row says so.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line: the start delay, the contribution per tower and its scaling, the"
            + " full bar's attempt, the target and the tower choices, the gate of an attacking"
            + " tower, the throw's countdown and its start toward the target, the bar's drop and"
            + " the target that leaves; a destroyed tower leaves the side's list at its removal."
            + " Held: a Giant cooked for while walking, a Giant chosen over a Musketeer and the"
            + " second pancake that follows, a damaged Giant, and the slower cooking after a"
            + " tower falls. Not modelled: the bar's share that switches the towers' animation,"
            + " which only shows something. Refused: the row columns no shipped row sets.")
public final class ChefCooking extends RowAction {

  /** The step every countdown loses. */
  private static final int STEP_MS = 50;

  /** What a full bar holds is the needed contribution times this. */
  private static final int BAR_SCALE = 20;

  /** The state a candidate is skipped in. */
  private static final int SKIPPED_STATE = 6;

  /** No object chosen. */
  private static final int NONE = -1;

  /**
   * The row's own columns.
   *
   * @param startCookingDelayMs the wait before the first contribution
   * @param contributionNeeded what a full bar holds, divided by 20
   * @param contributionBaseline what every cooking step adds
   * @param contributionIdle what a tower that is not attacking adds, before its scaling
   * @param contributionAttacking what an attacking tower adds, before its scaling
   * @param contributionDestroyed what each tower short of two adds
   * @param targetFilter the filter of the candidates, or null for no target ever
   * @param minCurrentHpThreshold the hit points a candidate must have more than, before scaling
   * @param minCurrentHpPercentage the share of its maximum a candidate must have at least
   * @param minMaxHpThreshold the maximum a candidate must have more than, before scaling
   * @param deprioritizeBuffed true when a candidate with fewer of the projectile's buff wins
   * @param buffProjectile the projectile thrown
   * @param pancakeThrowDelayMs the countdown from a throw's start to the projectile
   * @param pancakeStartOffset how far from the tower toward the target the projectile starts
   * @param pancakeThrowDelayThresholdMs how close to its next hit an attacking tower holds a throw
   * @param waitPancakeThrowAfterAttackMs how long after its load an attacking tower holds a throw
   * @param finishWhenBothTowersLost true when the run ends with no tower left
   */
  @Builder
  public record Columns(
      int startCookingDelayMs,
      int contributionNeeded,
      int contributionBaseline,
      int contributionIdle,
      int contributionAttacking,
      int contributionDestroyed,
      GameObjectFilter targetFilter,
      int minCurrentHpThreshold,
      int minCurrentHpPercentage,
      int minMaxHpThreshold,
      boolean deprioritizeBuffed,
      ProjectileData buffProjectile,
      int pancakeThrowDelayMs,
      int pancakeStartOffset,
      int pancakeThrowDelayThresholdMs,
      int waitPancakeThrowAfterAttackMs,
      boolean finishWhenBothTowersLost) {}

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public ChefCooking(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(this, holder.getOwner().cookingHost());
  }

  /** One run: its start delay, its bar, and the throw it waits on. */
  public final class Run extends ActionInstance {

    private final CookingHost host;
    private final int full = columns.contributionNeeded() * BAR_SCALE;
    private int delayMs = columns.startCookingDelayMs();
    private int bar;
    private int towerId = NONE;
    private int throwMs = -1;
    private int targetId = NONE;

    private Run(BattleAction action, CookingHost host) {
      super(action);
      this.host = host;
    }

    /**
     * What the bar holds: 0 through the start delay, at or past {@link #fullBar()} from the step it
     * is full until a throw goes, and what ran past a full bar after it.
     */
    public int bar() {
      return bar;
    }

    /** What a full bar holds: the row's needed contribution times 20. */
    public int fullBar() {
      return full;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (delayMs >= 1) {
        delayMs -= STEP_MS;
        return;
      }
      if (throwMs >= 0) {
        int old = throwMs;
        throwMs = old - STEP_MS;
        // The countdown is compared unsigned: a throw goes on the step that finds 50 or less.
        if (Integer.compareUnsigned(old, STEP_MS) > 0) {
          return;
        }
        if (targetId == NONE) {
          targetId = chooseTarget();
          if (targetId == NONE) {
            return;
          }
        }
        throwMs = -1;
        if (!host.found(towerId)) {
          return;
        }
        host.throwAt(columns.buffProjectile(), towerId, targetId, columns.pancakeStartOffset());
        bar -= full;
        return;
      }
      int add = columns.contributionBaseline();
      List<Integer> towers = host.towers();
      for (int tower : towers) {
        int contribution =
            host.attacking(tower) ? columns.contributionAttacking() : columns.contributionIdle();
        add += host.scaled(tower, contribution);
      }
      int count = towers.size();
      if (count == 0 && columns.finishWhenBothTowersLost()) {
        finish();
        return;
      }
      if (bar >= full) {
        tryThrow();
        return;
      }
      bar = bar + add + columns.contributionDestroyed() * (2 - count);
    }

    @Override
    protected void objectLeft(int leftId) {
      if (leftId == targetId) {
        targetId = NONE;
      }
    }

    /** Starts a throw when there is a target, a tower and the tower's attack lets it go. */
    private void tryThrow() {
      int target = chooseTarget();
      if (target == NONE) {
        return;
      }
      int tower = chooseTower(target);
      if (tower == NONE || !host.found(tower)) {
        return;
      }
      if (!host.throwAllowed(
          tower, columns.waitPancakeThrowAfterAttackMs(), columns.pancakeThrowDelayThresholdMs())) {
        return;
      }
      targetId = target;
      towerId = tower;
      throwMs = columns.pancakeThrowDelayMs();
      host.turn(tower, target);
    }

    /** The candidate with the fewest of the projectile's buff, then the most hit points. */
    private int chooseTarget() {
      if (columns.targetFilter() == null) {
        return NONE;
      }
      String buff = columns.buffProjectile().targetBuff();
      int best = NONE;
      int bestCurrent = 0;
      int bestBuffs = Integer.MAX_VALUE;
      for (int id : host.candidates(columns.targetFilter())) {
        if (host.state(id) == SKIPPED_STATE || !host.hasHitPoints(id)) {
          continue;
        }
        int maximum = host.maximum(id);
        if (maximum <= host.scaledThreshold(id, columns.minMaxHpThreshold())) {
          continue;
        }
        int current = host.current(id);
        if (current <= host.scaledThreshold(id, columns.minCurrentHpThreshold())) {
          continue;
        }
        if (current * 100 / maximum < columns.minCurrentHpPercentage()) {
          continue;
        }
        int buffs = buff == null ? 0 : host.buffCount(id, buff);
        if (columns.deprioritizeBuffed() && buffs > bestBuffs) {
          continue;
        }
        if (current > bestCurrent || (columns.deprioritizeBuffed() && buffs < bestBuffs)) {
          best = id;
          bestCurrent = current;
          bestBuffs = buffs;
        }
      }
      return best;
    }

    /** A tower that is not attacking before one that is, then the strictly nearer. */
    private int chooseTower(int target) {
      int best = NONE;
      int bestDistance = Integer.MAX_VALUE;
      boolean bestAttacking = true;
      for (int tower : host.towers()) {
        int distance = host.squaredDistance(tower, target);
        boolean attacking = host.attacking(tower);
        if ((distance < bestDistance && attacking == bestAttacking)
            || (!attacking && bestAttacking)) {
          best = tower;
          bestDistance = distance;
          bestAttacking = attacking;
        }
      }
      return best;
    }
  }
}
