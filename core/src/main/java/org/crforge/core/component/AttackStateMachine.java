package org.crforge.core.component;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.crforge.core.card.AttackSequenceHit;

/**
 * Encapsulates the attack sequencing state machine: cooldown, windup, load time accumulation, and
 * multi-hit combo tracking. Extracted from Combat to separate runtime attack state from static
 * combat configuration.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttackStateMachine {

  @Builder.Default private float currentWindup = 0;
  @Builder.Default private float currentCooldown = 0;
  @Builder.Default private float accumulatedLoadTime = 0;
  @Builder.Default private boolean isAttacking = false;
  @Builder.Default private int attackSequenceIndex = 0;

  /** Convenience factory for the common case: only accumulatedLoadTime differs from defaults. */
  public static AttackStateMachine withLoad(float accumulatedLoadTime) {
    return AttackStateMachine.builder().accumulatedLoadTime(accumulatedLoadTime).build();
  }

  /**
   * Starts an attack sequence. Calculates windup based on attackCooldown and accumulatedLoadTime.
   */
  public void startAttackSequence(float attackCooldown) {
    // Formula: Windup = HitTime - Charge
    // Ensure we don't go below 0 (instant)
    float calculatedWindup = Math.max(0, attackCooldown - accumulatedLoadTime);
    this.currentWindup = calculatedWindup;
    this.isAttacking = true;
    // Charge is consumed for this attack
    this.accumulatedLoadTime = 0;
  }

  /** Finishes the current attack and advances the attack sequence index for multi-hit combos. */
  public void finishAttack(List<AttackSequenceHit> attackSequence) {
    this.currentCooldown = 0; // Immediate chaining if windup accounts for full duration
    this.isAttacking = false;
    if (!attackSequence.isEmpty()) {
      attackSequenceIndex = (attackSequenceIndex + 1) % attackSequence.size();
    }
  }

  /**
   * Resets the attack animation/load time. Used for Stun (Zap) mechanics. Unlike Freeze (which
   * pauses), Stun forces the unit to restart their attack windup.
   */
  public void resetAttackState() {
    this.currentWindup = 0;
    this.isAttacking = false;
    this.accumulatedLoadTime = 0;
  }

  /**
   * Updates timers: cooldown countdown, windup countdown, and load time accumulation.
   *
   * @param effectiveDelta Time passed, already scaled by attack speed multiplier (0 when disabled)
   * @param canAccumulateLoad If true, unit is moving/deploying/idle and can charge up
   * @param loadTime Maximum load time from combat config (cap for accumulation)
   */
  public void update(float effectiveDelta, boolean canAccumulateLoad, float loadTime) {
    if (currentCooldown > 0) {
      currentCooldown -= effectiveDelta;
    }
    if (isAttacking) {
      currentWindup -= effectiveDelta;
    } else if (canAccumulateLoad && effectiveDelta > 0) {
      accumulatedLoadTime += effectiveDelta;
      if (accumulatedLoadTime > loadTime) {
        accumulatedLoadTime = loadTime;
      }
    }
  }

  /** Returns true if the unit can attack (not disabled, cooldown elapsed). */
  public boolean canAttack(boolean combatDisabled) {
    return !combatDisabled && currentCooldown <= 0;
  }

  /** Returns true if the unit is currently winding up an attack. */
  public boolean isWindingUp() {
    return currentWindup > 0;
  }

  /** Returns the effective damage for the current attack, using attack sequence if available. */
  public int getEffectiveDamage(int baseDamage, List<AttackSequenceHit> attackSequence) {
    if (!attackSequence.isEmpty()) {
      return attackSequence.get(attackSequenceIndex).damage();
    }
    return baseDamage;
  }
}
