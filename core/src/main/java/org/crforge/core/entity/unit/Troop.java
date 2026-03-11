package org.crforge.core.entity.unit;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;

@Getter
@SuperBuilder
public class Troop extends AbstractEntity {

  @Builder.Default private final Combat combat = Combat.builder().build();

  @Builder.Default private final float deployTime = DEFAULT_DEPLOY_TIME;

  @Builder.Default private float deployTimer = 1.0f;

  @Builder.Default private final AbilityComponent ability = null;

  // River jump state: true while the troop is leaping over the river
  @Setter private boolean jumping;

  @Override
  public EntityType getEntityType() {
    return EntityType.TROOP;
  }

  @Override
  public MovementType getMovementType() {
    // While jumping, behave as AIR for pathfinding, collision, and targeting
    return jumping ? MovementType.AIR : super.getMovementType();
  }

  public boolean isDeploying() {
    return deployTimer > 0;
  }

  public boolean isInAttackRange() {
    if (combat == null) {
      return false;
    }
    Entity currentTarget = combat.getCurrentTarget();
    if (currentTarget == null) {
      return false;
    }
    float distance = position.distanceTo(currentTarget.getPosition());
    // Collision Radius used for attack range calculation
    float effectiveRange =
        combat.getRange() + getCollisionRadius() + currentTarget.getCollisionRadius();
    return distance <= effectiveRange;
  }

  public float getDistanceToTarget() {
    if (combat == null) {
      return Float.MAX_VALUE;
    }
    Entity currentTarget = combat.getCurrentTarget();
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
      // Troops accumulate load time while deploying
      if (combat != null) {
        combat.update(deltaTime, true);
      }
      return;
    }

    // Update combat
    if (combat != null) {
      // Pass true to allow accumulating load time if not attacking
      combat.update(deltaTime, true);
    }
  }

  @Override
  public void onSpawn() {
    super.onSpawn();
    if (deployTime <= 0) {
      deployTimer = 0;
    }
  }

  @Override
  public boolean isTargetable() {
    return super.isTargetable() && !isDeploying() && !jumping;
  }
}
