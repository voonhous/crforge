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
  private final float attackCooldown = 1.0f;
  @Builder.Default
  private final float firstAttackCooldown = 0f; // Custom initial windup
  @Builder.Default
  private final float aoeRadius = 0;
  @Builder.Default
  private final TargetType targetType = TargetType.ALL;
  @Builder.Default
  private final float loadTime = 0; // Standard windup for attacks

  @Builder.Default
  private final List<EffectStats> hitEffects = new ArrayList<>();

  private final ProjectileStats projectileStats;

  // Dynamic states
  private Entity currentTarget;
  private boolean targetLocked;

  private float currentCooldown;
  private float currentLoadTime;

  @Builder.Default
  private float attackSpeedMultiplier = 1.0f;
  @Builder.Default
  private boolean combatDisabled = false;

  // Track if we are performing the first attack on the current target
  @Builder.Default
  private boolean firstAttackOnTarget = true;

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
    // If target changes (or is set to null), reset first attack flag
    if (this.currentTarget != currentTarget) {
      this.currentTarget = currentTarget;
      this.targetLocked = currentTarget != null;
      if (this.currentTarget != null) {
        this.firstAttackOnTarget = true;
      }
      // Reset attack state on retarget
      this.isAttacking = false;
      this.currentLoadTime = 0;
    }
  }

  public void clearTarget() {
    setCurrentTarget(null);
  }

  public boolean canAttack() {
    return !combatDisabled && currentCooldown <= 0;
  }

  public boolean isLoading() {
    return currentLoadTime > 0;
  }

  public void startAttack(float duration) {
    currentLoadTime = duration;
    isAttacking = true;
  }

  // Legacy/Default overload
  public void startAttack() {
    startAttack(loadTime);
  }

  public void finishAttack() {
    currentCooldown = attackCooldown;
    currentLoadTime = 0;
    isAttacking = false;
    // We consumed the first attack
    firstAttackOnTarget = false;
  }

  /**
   * Resets the attack animation/load time. Used for Stun (Zap) mechanics. Unlike Freeze (which
   * pauses), Stun forces the unit to restart their attack windup.
   */
  public void resetAttackState() {
    this.currentLoadTime = 0;
    this.isAttacking = false;
    // Stun typically resets the windup, but does it reset "first hit" status if target didn't change?
    // In CR, zapping a Sparky resets charge. Zapping a troop usually just resets the current swing.
    // We won't reset 'firstAttackOnTarget' here to avoid exploiting first hit speed repeatedly on same target.
  }

  public void update(float deltaTime) {
    float effectiveDelta = deltaTime * (combatDisabled ? 0 : attackSpeedMultiplier);
    if (currentCooldown > 0) {
      currentCooldown -= effectiveDelta;
    }
    if (currentLoadTime > 0) {
      currentLoadTime -= effectiveDelta;
    }
  }

  public void resetCooldown() {
    currentCooldown = attackCooldown;
  }
}
