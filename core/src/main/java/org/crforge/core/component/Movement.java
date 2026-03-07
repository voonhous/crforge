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

  @Getter
  private final float speed;
  private final float mass;
  private final float collisionRadius;
  private final float visualRadius;
  private final MovementType type;

  @Setter
  private boolean canMoveFlag = true;

  // Source-tracked movement disable -- any source present means movement is disabled
  private final EnumSet<ModifierSource> movementDisableSources =
      EnumSet.noneOf(ModifierSource.class);

  // Source-tracked speed multipliers -- effective multiplier is the product of all
  private final EnumMap<ModifierSource, Float> speedMultipliers =
      new EnumMap<>(ModifierSource.class);

  /**
   * Set movement disabled state for a specific source.
   */
  public void setMovementDisabled(ModifierSource source, boolean disabled) {
    if (disabled) {
      movementDisableSources.add(source);
    } else {
      movementDisableSources.remove(source);
    }
  }

  /**
   * Returns true if any source has disabled movement.
   */
  public boolean isMovementDisabled() {
    return !movementDisableSources.isEmpty();
  }

  /**
   * Set speed multiplier for a specific source.
   */
  public void setSpeedMultiplier(ModifierSource source, float multiplier) {
    if (multiplier == 1.0f) {
      speedMultipliers.remove(source);
    } else {
      speedMultipliers.put(source, multiplier);
    }
  }

  /**
   * Returns the product of all active speed multipliers.
   */
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

  /**
   * Clears all modifiers (disable + speed) for the given source.
   */
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
}
