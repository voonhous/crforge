package org.crforge.core.ability;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Mutable runtime state for an active GiantBuffer buff on a target troop. Tracks the attack counter
 * for proc timing, delay before activation, and persistence after the source dies.
 *
 * <p>This is a singleton buff: re-application by the same or different GiantBuffer refreshes the
 * state rather than stacking.
 */
@Getter
@Setter
public class GiantBuffState {

  private int attackCounter = 0;
  private final int addedDamage;
  private final int addedCrownTowerDamage;
  private final int attackAmount;
  private final List<DamageMultiplierEntry> damageMultipliers;
  private final long sourceEntityId;
  private float delayTimer;
  private boolean active = false;
  private boolean sourceDead = false;
  private float persistTimer = 0f;
  private final float persistDuration;

  public GiantBuffState(
      int addedDamage,
      int addedCrownTowerDamage,
      int attackAmount,
      List<DamageMultiplierEntry> damageMultipliers,
      long sourceEntityId,
      float delayTimer,
      float persistDuration) {
    this.addedDamage = addedDamage;
    this.addedCrownTowerDamage = addedCrownTowerDamage;
    this.attackAmount = attackAmount;
    this.damageMultipliers = damageMultipliers;
    this.sourceEntityId = sourceEntityId;
    this.delayTimer = delayTimer;
    this.persistDuration = persistDuration;
  }

  /** Refreshes the buff (singleton re-application). Resets counter and delay timer. */
  public void refresh(float newDelay) {
    this.attackCounter = 0;
    this.delayTimer = newDelay;
    this.active = false;
    this.sourceDead = false;
    this.persistTimer = 0f;
  }

  /** Returns true when the source is dead and the persist timer has expired. */
  public boolean isExpired() {
    return sourceDead && persistTimer >= persistDuration;
  }
}
