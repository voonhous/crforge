package org.crforge.core.battle.action;

import lombok.Getter;
import lombok.Setter;
import org.crforge.core.battle.spawn.SpawnHost;

/**
 * A hit as the damage entry tells it to the runs of the entity it reaches, after the two sides'
 * percentages and before the bookkeeping: its amount, which a run may change, whether it is a
 * reflected hit, and what it came from.
 */
public final class DamageHeard {

  /** The hit's amount; the entry deals nothing once a run has set it to 0. */
  @Getter @Setter private int amount;

  /** True for a hit whose damage is flagged Reflected. */
  private final boolean reflected;

  /** What the hit came from - a unit, a projectile, an area effect - or null for nothing. */
  private final SpawnHost source;

  /**
   * @param amount the hit's amount
   * @param reflected true for a reflected hit
   * @param source what the hit came from, or null for nothing
   */
  public DamageHeard(int amount, boolean reflected, SpawnHost source) {
    this.amount = amount;
    this.reflected = reflected;
    this.source = source;
  }

  /** True for a hit whose damage is flagged Reflected. */
  public boolean reflected() {
    return reflected;
  }

  /** What the hit came from, or null for nothing. */
  public SpawnHost source() {
    return source;
  }
}
