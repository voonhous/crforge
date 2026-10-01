package org.crforge.core.battle.unit;

import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.spawn.SpawnHost;

/**
 * One buff listed on an entity: its row, what is left of its time, its level, what applied it, and
 * the parent whose removal removes it.
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

  /**
   * The entity whose removal, or whose being removable, removes it: the area effect that applied a
   * buff its parent controls, kept only for a buff that stacks; null for none.
   */
  private BattleEntity parent;

  BuffInstance(String key, BuffData buff, int time, int packedLevel, SpawnHost source, int side) {
    this(key, buff, time, packedLevel, source, side, null);
  }

  BuffInstance(
      String key,
      BuffData buff,
      int time,
      int packedLevel,
      SpawnHost source,
      int side,
      BattleEntity parent) {
    this.key = key;
    this.buff = buff;
    this.remaining = time;
    this.total = time;
    this.packedLevel = packedLevel;
    this.source = source;
    this.side = side;
    // The instance keeps a parent only for a buff that stacks.
    this.parent = buff.enableStacking() ? parent : null;
  }

  /**
   * A copy of the instance under a new key, as a clone takes its original's buffs: the time it has
   * left and its whole time, the visits it has counted, its level, source, side and parent.
   *
   * @param key the copy's name in logs
   */
  BuffInstance copy(String key) {
    BuffInstance copy = new BuffInstance(key, buff, remaining, packedLevel, source, side, parent);
    copy.total = total;
    copy.hitCounter = hitCounter;
    return copy;
  }

  /**
   * Whether the visit removes it: its parent is removable, or its time has run out.
   *
   * @return true when it is finished
   */
  boolean finished() {
    if (parent != null && parent.isRemovable()) {
      return true;
    }
    return remaining == 0;
  }

  /** The parent is let go as the instance is removed. */
  void forgetParent() {
    parent = null;
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

  /**
   * Sets the visits counted toward the next hit from the age of the area effect that applied it, as
   * a buff whose hits follow their source asks on every visit: the age modulo the hit frequency, in
   * visits. The hit comes on the visit that counts up to the frequency, so every instance one area
   * effect applied is hit on the same visits, whenever it was applied.
   *
   * @param ageMs the source's age, as its last update left it
   * @param stepMs the milliseconds of one visit
   */
  void followSource(int ageMs, int stepMs) {
    int frequency = Math.max(buff.hitFrequency(), 0);
    // The remainder of a division by 0 is the dividend, as the game's own division answers it.
    hitCounter = (frequency == 0 ? ageMs : ageMs % frequency) / stepMs;
  }

  /** The source that left the battle is forgotten; the instance stays. */
  void forgetSource() {
    source = null;
  }
}
