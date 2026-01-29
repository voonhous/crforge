package org.crforge.core.component;

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
  private float speedMultiplier = 1.0f;
  @Setter
  private boolean canMoveFlag = true;

  @Setter
  private boolean movementDisabled = false;

  public float getBaseSpeed() {
    return speed;
  }

  public float getEffectiveSpeed() {
    return speed * speedMultiplier;
  }

  public void resetSpeedMultiplier() {
    this.speedMultiplier = 1.0f;
  }

  public boolean canMove() {
    return !movementDisabled && canMoveFlag && type != MovementType.BUILDING;
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
