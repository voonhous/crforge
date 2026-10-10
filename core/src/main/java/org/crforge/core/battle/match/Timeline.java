/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The battle's clock: where the battle stands in its timeline's sections, and which elixir rate and
 * next-card cooldown apply now.
 *
 * <p>The game-mode update advances it once a step, to the battle tick, so its time is 50 ms times
 * that tick. Each advance adds the time since the last to three counters - the section's, the
 * elixir rate's and the cooldown's - and moves each on to its next entry while the counter has
 * reached the entry's length, carrying what is left over. A section moves on into overtime only
 * while the crowns are equal, and never into bonus time. Every move reloads all three current
 * entries from the indices the timeline holds at that moment.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line: the advance's three loops, their carry, the"
            + " overtime gate and the reload of all three entries on every move, and the freeze."
            + " Held by the reference battles timeline_spells_through_rates and"
            + " timeline_overtime_crown: the rate at 2400. Not held: the sections' move into"
            + " overtime and the time running out, the forced rate step and bonus time, which the"
            + " Ladder timeline never asks for.")
public final class Timeline {

  private final BattleTimeline row;

  /** The event times in milliseconds, in the order they fall. */
  private final List<Integer> events = new ArrayList<>();

  /** The tick of the last advance, or -1 before the first. */
  private int lastTick = -1;

  /** The battle time in milliseconds. */
  @Getter private int timeMs;

  /** True on the advance that was the first. */
  @Getter private boolean firstAdvance;

  /** True once the end has frozen the timeline: its counters take no more time. */
  @Getter private boolean frozen;

  /** The battle time the freeze kept. */
  private int frozenTimeMs;

  /** Whether the crowns were equal at the last update, which lets a section move into overtime. */
  private boolean crownsEqual = true;

  /** Forced elixir-rate steps still to take. */
  private int forcedRateSteps;

  /** The current section. */
  @Getter private int section;

  /** Time spent in the current section, in milliseconds. */
  private int sectionElapsedMs;

  /** The current section's length in milliseconds. */
  private int sectionLengthMs;

  /** The current section's type. */
  @Getter private int sectionType;

  /** The current elixir rate. */
  @Getter private int rate;

  /** Time spent at the current elixir rate, in milliseconds. */
  private int rateElapsedMs;

  /** The current elixir rate's length in milliseconds. */
  private int rateLengthMs;

  /** The time the current elixir rate fills one bar in, in milliseconds. */
  @Getter private int fullBarMs;

  /** The current next-card cooldown. */
  private int cooldown;

  /** Time spent at the current next-card cooldown, in milliseconds. */
  private int cooldownElapsedMs;

  /** The current next-card cooldown's length in milliseconds. */
  private int cooldownLengthMs;

  /** The current next-card cooldown, in milliseconds. */
  @Getter private int nextCardCooldownMs;

  /** How many events the battle time has passed. */
  private int eventsPassed;

  /**
   * A timeline at its start: the first section, rate and cooldown loaded.
   *
   * @param row the game mode's battle timeline
   */
  public Timeline(BattleTimeline row) {
    this.row = row;
    for (int time : row.eventTimes()) {
      events.add(time * 1000);
    }
    // A stable exchange sort by time.
    for (int i = 0; i < events.size(); i++) {
      for (int j = i + 1; j < events.size(); j++) {
        if (events.get(j) < events.get(i)) {
          int kept = events.get(i);
          events.set(i, events.get(j));
          events.set(j, kept);
        }
      }
    }
    load(0, 0, 0);
  }

  /** Loads the three current entries from the given indices. */
  private void load(int section, int rate, int cooldown) {
    sectionLengthMs = row.sectionLengths().get(section) * 1000;
    sectionType = row.sectionTypes().get(section);
    rateLengthMs = row.rateLengths().get(rate) * 1000;
    fullBarMs = row.fullBarMs().get(rate);
    cooldownLengthMs = row.cooldownLengths().get(cooldown) * 1000;
    nextCardCooldownMs = row.cooldownMs().get(cooldown);
  }

  /**
   * Sets whether the crowns are equal, which the update does before each advance.
   *
   * @param equal true when both sides have taken as many crowns
   */
  public void setCrownsEqual(boolean equal) {
    crownsEqual = equal;
  }

  /**
   * Advances the timeline to a tick.
   *
   * @param tick the battle tick
   */
  public void advance(int tick) {
    firstAdvance = false;
    if (lastTick == tick) {
      return;
    }
    if (lastTick < 0) {
      firstAdvance = true;
    }
    int ms = tick * 50;
    int delta = ms - timeMs;
    lastTick = tick;
    timeMs = ms;
    if (frozen) {
      delta = 0;
    }
    advanceSection(delta);
    advanceRate(delta);
    advanceCooldown(delta);
    while (eventsPassed < events.size() && events.get(eventsPassed) <= ms) {
      eventsPassed++;
    }
  }

  private void advanceSection(int delta) {
    sectionElapsedMs += delta;
    if (sectionElapsedMs < sectionLengthMs || sectionLengthMs < 1) {
      return;
    }
    int current = section;
    int last = row.sectionLengths().size() - 1;
    int limit = Math.max(current, last);
    while (current != limit) {
      int next = row.sectionTypes().get(current + 1);
      if (next == BattleTimeline.BONUS_TIME) {
        break;
      }
      if (next == BattleTimeline.OVERTIME && !crownsEqual) {
        break;
      }
      sectionElapsedMs -= sectionLengthMs;
      section = current + 1;
      int length = row.sectionLengths().get(current + 1);
      load(current + 1, rate, cooldown);
      if (sectionElapsedMs < sectionLengthMs || length <= 0) {
        break;
      }
      current++;
    }
  }

  private void advanceRate(int delta) {
    rateElapsedMs += delta;
    if (rateElapsedMs < rateLengthMs && forcedRateSteps < 1) {
      return;
    }
    int current = rate;
    while (true) {
      if (rateLengthMs <= 0 && forcedRateSteps < 1) {
        break;
      }
      if (current >= row.rateLengths().size() - 1) {
        break;
      }
      rateElapsedMs -= rateLengthMs;
      if (forcedRateSteps > 0) {
        // A forced step starts the new rate afresh.
        rateElapsedMs = 0;
        forcedRateSteps--;
      }
      current++;
      rate = current;
      load(section, current, cooldown);
      if (rateElapsedMs < rateLengthMs && forcedRateSteps <= 0) {
        break;
      }
    }
  }

  private void advanceCooldown(int delta) {
    cooldownElapsedMs += delta;
    if (cooldownElapsedMs < cooldownLengthMs || cooldownLengthMs < 1) {
      return;
    }
    int current = cooldown;
    int last = row.cooldownLengths().size() - 1;
    int limit = Math.max(current, last);
    while (current != limit) {
      cooldownElapsedMs -= cooldownLengthMs;
      cooldown = current + 1;
      int length = row.cooldownLengths().get(current + 1);
      load(section, rate, current + 1);
      if (cooldownElapsedMs < cooldownLengthMs || length <= 0) {
        break;
      }
      current++;
    }
  }

  /** Freezes the timeline, as the end does: its counters take no more time. */
  public void freeze() {
    if (frozen) {
      return;
    }
    frozen = true;
    frozenTimeMs = timeMs;
  }

  /** True in an overtime section. */
  public boolean overtime() {
    return sectionType == BattleTimeline.OVERTIME;
  }

  /**
   * True when the time is up: the current section has run out and is the last, or the next one may
   * not be entered - bonus time, or overtime with the crowns unequal.
   */
  public boolean timeUp() {
    if (sectionLengthMs < 1 || sectionElapsedMs < sectionLengthMs) {
      return false;
    }
    if (section >= row.sectionLengths().size() - 1) {
      return true;
    }
    int next = row.sectionTypes().get(section + 1);
    return next == BattleTimeline.BONUS_TIME || next == BattleTimeline.OVERTIME && !crownsEqual;
  }

  /** The row the timeline runs. */
  public BattleTimeline row() {
    return row;
  }
}
