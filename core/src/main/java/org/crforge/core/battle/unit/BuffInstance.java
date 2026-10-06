package org.crforge.core.battle.unit;

import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.projectile.ProjectileEntity;
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
   * The id of the entity that launched the projectile that applied it, kept as it was applied and
   * after the projectile has gone; -1 when no projectile applied it, or one with no launcher left.
   */
  private int sourceLauncherId = -1;

  /**
   * The entity whose removal, or whose being removable, removes it: the area effect that applied a
   * buff its parent controls, kept only for a buff that stacks; null for none.
   */
  private BattleEntity parent;

  /**
   * The spawner's timer: what is left before its next firing, in milliseconds, from the buff's
   * start time as the instance is listed.
   */
  private int spawnTimer;

  /** How many firings of the current wave the spawner has made. */
  private int spawnWaveMade;

  /** How many firings the spawner has left, from the buff's limit as the instance is listed. */
  private int spawnsLeft;

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
    if (source instanceof ProjectileEntity projectile && projectile.getOwner() != null) {
      this.sourceLauncherId = projectile.getOwner().getId();
    }
    // The instance keeps a parent only for a buff that stacks.
    this.parent = buff.enableStacking() ? parent : null;
    // The new instance's spawner: its start time and its limit.
    this.spawnTimer = buff.spawnStartTimeMs();
    this.spawnsLeft = buff.spawnLimit();
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
    copy.sourceLauncherId = sourceLauncherId;
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

  /**
   * Whether the visit asks its buff's life condition now, after the step of its time: only of an
   * instance that runs out and has time left.
   */
  boolean asksLifeCondition() {
    return remaining != FOREVER && remaining != 0;
  }

  /** A life condition that answered 0: its time is spent, and the visit removes it. */
  void expire() {
    remaining = 0;
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

  /**
   * One visit's step of the spawner's timer: for a buff with a spawn, an interval of at least 1 and
   * firings left, the timer loses half the carrier's spawn rate; it fires when that leaves it at 0
   * or below.
   *
   * @param spawnRate the carrier's spawn time percent, 100 without a buff
   * @return true when the spawner fires on this visit
   */
  boolean stepSpawner(int spawnRate) {
    if (buff.spawnObject() == null || buff.spawnIntervalMs() < 1 || spawnsLeft < 1) {
      return false;
    }
    // Halved toward zero, as the game's signed shift after adding the sign bit does.
    spawnTimer -= spawnRate / 2;
    return spawnTimer <= 0;
  }

  /**
   * What a firing leaves of the spawner: one more of the wave made and one firing fewer for a
   * limited spawner; the next firing the interval away within a wave, or the pause away once the
   * wave is made, and never less than 1 ms away; a timer that went below 0 carries.
   */
  void spawnerFired() {
    spawnWaveMade++;
    if (buff.spawnLimit() >= 1) {
      spawnsLeft--;
    }
    int next;
    if (spawnWaveMade < buff.spawnNumber()) {
      next = buff.spawnIntervalMs();
    } else {
      spawnWaveMade = 0;
      next = buff.spawnPauseTimeMs();
    }
    spawnTimer = spawnTimer + next > 1 ? spawnTimer + next : 1;
  }

  /**
   * Whether the entity of the given id applied it, as a filter's buff checker asks: it is the
   * source still known, or it launched the projectile that applied it.
   */
  boolean appliedBy(int id) {
    return source instanceof BattleEntity entity && entity.getId() == id
        || sourceLauncherId != -1 && sourceLauncherId == id;
  }

  /** The source that left the battle is forgotten; the instance stays. */
  void forgetSource() {
    source = null;
  }
}
