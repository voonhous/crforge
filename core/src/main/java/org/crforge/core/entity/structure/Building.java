package org.crforge.core.entity.structure;

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
public class Building extends AbstractEntity {

  private final Combat combat;
  @Builder.Default
  private final float lifetime = 0f;

  // Note: We use @Builder.Default for logic fields we want to initialize
  // based on the lifetime passed to builder
  @Builder.Default
  private float remainingLifetime = 0f;

  @Setter
  private Entity currentTarget;

  // Accumulator for fractional health decay
  @Builder.Default
  private float decayAccumulator = 0f;

  @Override
  public EntityType getEntityType() {
    return EntityType.BUILDING;
  }

  // Need to sync remainingLifetime with lifetime on build or spawn
  // AbstractEntity constructor runs before this logic in manual code,
  // but with SuperBuilder we might need to set remainingLifetime explicitly in DeploymentSystem
  // or use an initializer block if we can.
  // For simplicity, we'll initialize it in onSpawn or assume the builder sets it.

  public boolean hasLifetime() {
    return lifetime > 0;
  }

  public boolean isExpired() {
    return hasLifetime() && remainingLifetime <= 0;
  }

  public boolean hasTarget() {
    return currentTarget != null && currentTarget.isAlive();
  }

  public void clearTarget() {
    this.currentTarget = null;
  }

  @Override
  public void onSpawn() {
    super.onSpawn();
    if (remainingLifetime == 0 && lifetime > 0) {
      remainingLifetime = lifetime;
    }
  }

  @Override
  public void update(float deltaTime) {
    if (dead) {
      return;
    }

    // Reduce lifetime and apply health decay
    if (hasLifetime()) {
      remainingLifetime -= deltaTime;

      // Calculate decay
      // Rate: MaxHP / TotalLifetime (damage per second)
      float decayRate = (float) health.getMax() / lifetime;
      float decayAmount = decayRate * deltaTime;

      decayAccumulator += decayAmount;

      if (decayAccumulator >= 1.0f) {
        int damage = (int) decayAccumulator;
        health.takeDamage(damage);
        decayAccumulator -= damage;
      }

      // Also check explicit lifetime expiry as a failsafe or for logic that depends on time
      if (remainingLifetime <= 0 || health.isDead()) {
        remainingLifetime = 0;
        markDead();
      }
    } else {
      // For non-lifetime buildings (if any, e.g. King Tower), just check health
      if (health.isDead()) {
        markDead();
      }
    }

    // Update combat
    if (combat != null) {
      combat.update(deltaTime);
    }
  }
}
