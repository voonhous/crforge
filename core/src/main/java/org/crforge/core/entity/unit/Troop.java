package org.crforge.core.entity.unit;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;

@Getter
@SuperBuilder
public class Troop extends AbstractEntity {

  @Builder.Default
  private final Combat combat = Combat.builder().build();

  @Builder.Default
  private final float deployTime = 1.0f;

  @Setter
  private Entity currentTarget;
  @Setter
  private boolean targetLocked;

  @Builder.Default
  private float deployTimer = 1.0f;

  @Override
  public EntityType getEntityType() {
    return EntityType.TROOP;
  }

  public boolean hasTarget() {
    return currentTarget != null && currentTarget.isAlive();
  }

  public void clearTarget() {
    this.currentTarget = null;
    this.targetLocked = false;
  }

  public boolean isDeploying() {
    return deployTimer > 0;
  }

  public boolean isInAttackRange() {
    if (currentTarget == null) {
      return false;
    }
    float distance = position.distanceTo(currentTarget.getPosition());
    float effectiveRange = combat.getRange() + (getSize() + currentTarget.getSize()) / 2f;
    return distance <= effectiveRange;
  }

  public float getDistanceToTarget() {
    if (currentTarget == null) {
      return Float.MAX_VALUE;
    }
    return position.distanceTo(currentTarget.getPosition());
  }

  @Override
  public void update(float deltaTime) {
    if (dead) {
      return;
    }

    // Handle deploy timer
    if (deployTimer > 0) {
      deployTimer -= deltaTime;
      if (deployTimer <= 0) {
        deployTimer = 0;
        spawned = true;
      }
      return;
    }

    // Update combat cooldowns
    if (combat != null) {
      combat.update(deltaTime);
    }
  }

  @Override
  public void onSpawn() {
    super.onSpawn();
    // Sync deploy timer with deploy time if not manually set
    if (deployTimer == 0 && deployTime > 0) {
      deployTimer = deployTime;
    }
    if (deployTime <= 0) {
      deployTimer = 0;
    }
  }

  @Override
  public boolean isTargetable() {
    return super.isTargetable() && !isDeploying();
  }
}
