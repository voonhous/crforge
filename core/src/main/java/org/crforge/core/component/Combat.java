package org.crforge.core.component;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.TargetType;

@Getter
@Builder
@Setter
public class Combat {

  @Builder.Default
  private final int damage = 0;
  @Builder.Default
  private final float range = 1.0f;
  @Builder.Default
  private final float sightRange = 5.5f;
  @Builder.Default
  private final float attackCooldown = 1.0f; // "Hit Speed"
  @Builder.Default
  private final float loadTime = 0f; // Hidden stat: charges up to reduce first hit delay
  @Builder.Default
  private final float aoeRadius = 0;
  @Builder.Default
  private final TargetType targetType = TargetType.ALL;

  @Builder.Default
  private final List<EffectStats> hitEffects = new ArrayList<>();

  private final ProjectileStats projectileStats;

  // Combat modifiers
  @Builder.Default
  private final int multipleTargets = 0;
  @Builder.Default
  private final int multipleProjectiles = 0;

  // Buff applied to target when dealing damage (e.g. EWiz stun on hit)
  private final EffectStats buffOnDamage;

  // Targeting and combat modifiers
  @Builder.Default
  private final boolean targetOnlyBuildings = false;
  @Builder.Default
  private final float minimumRange = 0f;
  @Builder.Default
  private final int crownTowerDamagePercent = 0;

  // Dynamic states
  private Entity currentTarget;
  private boolean targetLocked;

  private float currentCooldown; // Time remaining in "Hit Speed" wait after an attack
  private float currentWindup;   // Time remaining in "Attack Animation" before damage

  @Builder.Default
  private float accumulatedLoadTime = 0f; // Time charged while moving/deploying/idling

  @Builder.Default
  private float attackSpeedMultiplier = 1.0f;
  @Builder.Default
  private boolean combatDisabled = false;

  // Track if we are currently in the middle of an attack sequence (winding up)
  @Builder.Default
  private boolean isAttacking = false;

  /**
   * Returns true if the unit is considered ranged (Range >= 2.0 tiles).
   */
  public boolean isRanged() {
    return range >= 2.0f;
  }

  public boolean hasTarget() {
    return currentTarget != null && currentTarget.isAlive();
  }

  public void setCurrentTarget(Entity currentTarget) {
    if (this.currentTarget != currentTarget) {
      this.currentTarget = currentTarget;
      this.targetLocked = currentTarget != null;
      // Reset attack state on retarget
      this.isAttacking = false;
      this.currentWindup = 0;
      // Do NOT reset accumulatedLoadTime here; moving to new target preserves charge.
    }
  }

  public void clearTarget() {
    setCurrentTarget(null);
  }

  public boolean canAttack() {
    return !combatDisabled && currentCooldown <= 0;
  }

  public boolean isWindingUp() {
    return currentWindup > 0;
  }

  /**
   * Starts an attack sequence.
   * Calculates windup based on attackCooldown and accumulatedLoadTime.
   */
  public void startAttackSequence() {
    // Formula: Windup = HitTime - Charge
    // Ensure we don't go below 0 (instant)
    float calculatedWindup = Math.max(0, attackCooldown - accumulatedLoadTime);

    this.currentWindup = calculatedWindup;
    this.isAttacking = true;

    // Charge is consumed for this attack
    this.accumulatedLoadTime = 0;
  }

  public void finishAttack() {
    // Attack finished. Reset state.
    this.currentCooldown = 0; // Immediate chaining if windup accounts for full duration
    this.isAttacking = false;
  }

  /**
   * Updates timers and charge.
   * @param deltaTime Time passed
   * @param canAccumulateLoad If true, we are moving/deploying/idle and can charge up.
   */
  public void update(float deltaTime, boolean canAccumulateLoad) {
    float effectiveDelta = deltaTime * (combatDisabled ? 0 : attackSpeedMultiplier);

    if (currentCooldown > 0) {
      currentCooldown -= effectiveDelta;
    }

    if (isAttacking) {
      currentWindup -= effectiveDelta;
    } else if (canAccumulateLoad && !combatDisabled) {
      // Charge up logic
      accumulatedLoadTime += effectiveDelta;
      if (accumulatedLoadTime > loadTime) {
        accumulatedLoadTime = loadTime;
      }
    }
  }

  /**
   * Resets the attack animation/load time. Used for Stun (Zap) mechanics. Unlike Freeze (which
   * pauses), Stun forces the unit to restart their attack windup.
   */
  public void resetAttackState() {
    this.currentWindup = 0;
    this.isAttacking = false;
    this.accumulatedLoadTime = 0; // Stun resets charge
  }

  public void resetCooldown() {
    currentCooldown = attackCooldown;
  }
}
