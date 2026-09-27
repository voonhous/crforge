package org.crforge.core.battle.unit;

import lombok.Getter;
import org.crforge.core.battle.spawn.SpawnHost;

/**
 * One buff listed on an entity: its row, what is left of its time, its level, and what applied it.
 */
@Getter
public final class BuffInstance {

  /** The time of an instance that never runs out. */
  public static final int FOREVER = -1;

  /** Its name in logs, given in the order the battle lists instances. */
  private final String key;

  private final BuffData buff;

  /** What is left of its time in milliseconds, or {@link #FOREVER}. */
  private int remaining;

  /** Its whole time, which a refresh lengthens by what it adds. */
  private int total;

  /** The visits counted toward its next hit of damage over time. */
  private int hitCounter;

  /** Its level, packed against the buff's rarity. */
  private int packedLevel;

  /** What applied it, or null once that has left the battle. */
  private SpawnHost source;

  /** The side it was applied for. */
  private final int side;

  BuffInstance(String key, BuffData buff, int time, int packedLevel, SpawnHost source, int side) {
    this.key = key;
    this.buff = buff;
    this.remaining = time;
    this.total = time;
    this.packedLevel = packedLevel;
    this.source = source;
    this.side = side;
  }

  /**
   * A re-application: a longer time replaces the remaining one, never on an instance that never
   * runs out, and lengthens the whole time by the difference; a higher level replaces the level.
   */
  void refresh(int time, int packedLevel) {
    if (time > remaining && remaining != FOREVER) {
      total += time - remaining;
      remaining = time;
    }
    if ((byte) this.packedLevel < (byte) packedLevel) {
      this.packedLevel = packedLevel;
    }
  }

  /** One visit's step of its time: 50 ms off, never below 0; one that never runs out keeps it. */
  void step(int stepMs) {
    if (remaining != FOREVER) {
      remaining = Math.max(remaining, stepMs) - stepMs;
    }
  }

  /**
   * Counts one visit toward the damage over time and answers the period of a hit due now, or 0 for
   * none: every hit frequency over 50 visits; a negative frequency one hit of a whole second on the
   * first visit.
   */
  int countHit(int stepMs) {
    int frequency = Math.max(buff.hitFrequency(), 0);
    boolean once = frequency < 1 || buff.hitFrequency() < 0;
    if (frequency <= 0 && buff.hitFrequency() >= 0) {
      return 0;
    }
    hitCounter++;
    if (hitCounter == 1 && once) {
      return 1000;
    }
    if (hitCounter < frequency / stepMs || once) {
      return 0;
    }
    hitCounter = 0;
    return frequency;
  }

  /** The source that left the battle is forgotten; the instance stays. */
  void forgetSource() {
    source = null;
  }
}
