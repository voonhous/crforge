/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts on an area effect and strikes what stands around it at a fixed rate, as Dark
 * Magic does: the fewer it finds, the stronger the action it runs on each of them. It does nothing
 * as it starts but list its run, whose timer starts at the hit frequency less the first hit's
 * delay.
 *
 * <p>Each step of the run lists the objects its query finds around the area effect's point, afresh.
 * Once the timer has reached the hit frequency, the timer goes back to 0 and the run fires: the
 * count of listed objects picks the first entry of the per-list maxima at or above it, else the
 * index past the last, and the action list's entry at that index, or its last, is scheduled on
 * every listed object in the list's order, the area effect as the cause. Every step then adds 50 to
 * the timer.
 *
 * <p>Refused as the row is built: a detection kept from one step to the next, the detection's reset
 * after a hit, a cooldown after it, an action list run on the area effect itself, a singleton, a
 * next action and tags, none of which a reference holds; and a row without a filter. As it starts:
 * an owner other than an area effect.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the timer's start, the query on every step, the fire at the hit"
            + " frequency, the pick by count and the schedules on every listed object; held by"
            + " the random reference battles random_battle16_s0010, random_battle16_s0013,"
            + " random_battle16_s0014 and random_battle16_s0024, which play Dark Magic. Refused:"
            + " a kept detection, its reset after a hit, a cooldown after it, an action list on"
            + " the owner, a singleton, a next action, tags and a missing filter.")
public final class LaserBall extends RowAction {

  /** The step every timer takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /**
   * The row's own columns.
   *
   * @param detectionRadius the radius of the query around the area effect's point
   * @param firstHitDelayMs how long after the start the first fire comes, against the frequency
   * @param hitFrequencyMs the time between two fires
   * @param hitFilter the filter its query asks
   * @param maxUnitPerActionList the largest count each action list's entry is picked for, in order
   * @param onDetectedUnitActionList the actions it runs on what it found, picked by the count
   */
  @Builder
  public record Columns(
      int detectionRadius,
      int firstHitDelayMs,
      int hitFrequencyMs,
      GameObjectFilter hitFilter,
      List<Integer> maxUnitPerActionList,
      List<BattleAction> onDetectedUnitActionList) {

    public Columns {
      maxUnitPerActionList = List.copyOf(maxUnitPerActionList);
      onDetectedUnitActionList = List.copyOf(onDetectedUnitActionList);
    }
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public LaserBall(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    Run run = new Run(holder.getOwner().laserBallHost());
    run.host.laserStarted(this, holder.passPhase(), run.timerMs);
    return run;
  }

  /**
   * The index the count of listed objects picks: the first whose maximum is at or above the count,
   * else the one past the last.
   */
  public int pick(int count) {
    List<Integer> caps = columns.maxUnitPerActionList();
    for (int i = 0; i < caps.size(); i++) {
      if (caps.get(i) >= count) {
        return i;
      }
    }
    return caps.size();
  }

  /** The action list's entry at the index, or its last past the end; null for an empty list. */
  BattleAction element(int index) {
    List<BattleAction> list = columns.onDetectedUnitActionList();
    if (list.isEmpty()) {
      return null;
    }
    return list.get(Math.min(index, list.size() - 1));
  }

  /** One run: its timer and the objects its last step listed. */
  private final class Run extends ActionInstance {

    private final LaserBallHost host;
    private int timerMs = columns.hitFrequencyMs() - columns.firstHitDelayMs();
    private final List<Integer> ids = new ArrayList<>();

    private Run(LaserBallHost host) {
      super(LaserBall.this);
      this.host = host;
    }

    @Override
    protected void update(ActionHolder holder) {
      int before = timerMs;
      ids.clear();
      ids.addAll(host.detect(columns.detectionRadius(), columns.hitFilter()));
      if (timerMs >= columns.hitFrequencyMs()) {
        timerMs = 0;
        int index = pick(ids.size());
        BattleAction action = element(index);
        List<Integer> targets = new ArrayList<>();
        if (action != null) {
          for (int id : ids) {
            host.schedule(id, action);
            targets.add(id);
          }
        }
        timerMs += STEP_MS;
        host.laserFired(
            LaserBall.this,
            ids.size(),
            index,
            targets,
            targets.isEmpty() ? null : action,
            before,
            timerMs);
        return;
      }
      timerMs += STEP_MS;
    }
  }
}
