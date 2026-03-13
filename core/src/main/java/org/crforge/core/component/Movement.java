package org.crforge.core.component;

import java.util.EnumMap;
import java.util.EnumSet;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.crforge.core.entity.base.MovementType;

@Getter
@RequiredArgsConstructor
public class Movement {

  @Getter private final float speed;
  private final float mass;
  private final float collisionRadius;
  private final float visualRadius;
  private final MovementType type;

  @Setter private boolean canMoveFlag = true;

  @Setter private boolean ignorePushback;

  @Setter private boolean jumpEnabled;

  @Setter private boolean hovering;

  // Knockback displacement state
  private float knockbackDirX;
  private float knockbackDirY;
  private float knockbackSpeed; // tiles per second
  private float knockbackTimeRemaining;

  // Attack dash state (lunge toward target when attack starts, e.g. Bat)
  // Two-phase: LUNGING forward, then RETURNING back to origin.
  private enum AttackDashPhase {
    LUNGING,
    RETURNING
  }

  private float attackDashDirX;
  private float attackDashDirY;
  private float attackDashSpeed;
  private float attackDashTimeRemaining;
  private AttackDashPhase attackDashPhase;
  private float attackDashDuration; // duration per phase (used to reset timer for return)

  // Source-tracked movement disable -- any source present means movement is disabled
  private final EnumSet<ModifierSource> movementDisableSources =
      EnumSet.noneOf(ModifierSource.class);

  // Source-tracked speed multipliers -- effective multiplier is the product of all
  private final EnumMap<ModifierSource, Float> speedMultipliers =
      new EnumMap<>(ModifierSource.class);

  /** Set movement disabled state for a specific source. */
  public void setMovementDisabled(ModifierSource source, boolean disabled) {
    if (disabled) {
      movementDisableSources.add(source);
    } else {
      movementDisableSources.remove(source);
    }
  }

  /** Returns true if any source has disabled movement. */
  public boolean isMovementDisabled() {
    return !movementDisableSources.isEmpty();
  }

  /** Set speed multiplier for a specific source. */
  public void setSpeedMultiplier(ModifierSource source, float multiplier) {
    if (multiplier == 1.0f) {
      speedMultipliers.remove(source);
    } else {
      speedMultipliers.put(source, multiplier);
    }
  }

  /** Returns the product of all active speed multipliers. */
  public float getSpeedMultiplier() {
    if (speedMultipliers.isEmpty()) {
      return 1.0f;
    }
    float product = 1.0f;
    for (float mult : speedMultipliers.values()) {
      product *= mult;
    }
    return product;
  }

  /** Clears all modifiers (disable + speed) for the given source. */
  public void clearModifiers(ModifierSource source) {
    movementDisableSources.remove(source);
    speedMultipliers.remove(source);
  }

  public float getBaseSpeed() {
    return speed;
  }

  public float getEffectiveSpeed() {
    return speed * getSpeedMultiplier();
  }

  public void resetSpeedMultiplier() {
    speedMultipliers.clear();
  }

  public boolean canMove() {
    return !isMovementDisabled() && canMoveFlag && type != MovementType.BUILDING;
  }

  public boolean isBuilding() {
    return type == MovementType.BUILDING;
  }

  public boolean isAir() {
    return type == MovementType.AIR;
  }

  public boolean isGround() {
    return type == MovementType.GROUND;
  }

  public boolean isAttackDashing() {
    return attackDashTimeRemaining > 0;
  }

  /** Starts an attack dash (lunge) toward the target when the attack windup begins. */
  public void startAttackDash(float dirX, float dirY, float speed, float duration) {
    this.attackDashDirX = dirX;
    this.attackDashDirY = dirY;
    this.attackDashSpeed = speed;
    this.attackDashDuration = duration;
    this.attackDashTimeRemaining = duration;
    this.attackDashPhase = AttackDashPhase.LUNGING;
  }

  /**
   * Ticks the attack dash displacement, moving the position each frame. During the LUNGING phase
   * the entity moves toward the target; when that timer expires it flips to the RETURNING phase
   * with reversed direction and the same speed/duration so the entity snaps back to its origin.
   */
  public void tickAttackDash(Position position, float deltaTime) {
    if (attackDashTimeRemaining <= 0) {
      return;
    }
    position.add(
        attackDashDirX * attackDashSpeed * deltaTime, attackDashDirY * attackDashSpeed * deltaTime);
    attackDashTimeRemaining -= deltaTime;

    // When the current phase timer expires, transition or finish
    if (attackDashTimeRemaining <= 0) {
      if (attackDashPhase == AttackDashPhase.LUNGING) {
        // Flip to return phase: reverse direction, reset timer
        attackDashDirX = -attackDashDirX;
        attackDashDirY = -attackDashDirY;
        attackDashTimeRemaining = attackDashDuration;
        attackDashPhase = AttackDashPhase.RETURNING;
      }
      // RETURNING phase expired -> dash is fully done (timeRemaining stays <= 0)
    }
  }

  public boolean isKnockedBack() {
    return knockbackTimeRemaining > 0;
  }

  /**
   * Starts a knockback displacement in the given direction.
   *
   * @param dirX normalized X direction
   * @param dirY normalized Y direction
   * @param distance total displacement distance in tiles
   * @param duration active knockback duration in seconds
   * @param maxTime time base for speed calculation (distance / maxTime = speed)
   */
  public void startKnockback(
      float dirX, float dirY, float distance, float duration, float maxTime) {
    this.knockbackDirX = dirX;
    this.knockbackDirY = dirY;
    this.knockbackSpeed = distance / maxTime;
    this.knockbackTimeRemaining = duration;
    // Knockback overrides any active attack dash
    this.attackDashTimeRemaining = 0;
  }

  /** Ticks the knockback displacement, moving the position each frame. */
  public void tickKnockback(Position position, float deltaTime) {
    if (knockbackTimeRemaining > 0) {
      position.add(
          knockbackDirX * knockbackSpeed * deltaTime, knockbackDirY * knockbackSpeed * deltaTime);
      knockbackTimeRemaining -= deltaTime;
    }
  }
}
