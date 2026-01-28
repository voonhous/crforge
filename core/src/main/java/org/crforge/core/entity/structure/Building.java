package org.crforge.core.entity.structure;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.AbstractEntity;
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

  // Accumulator for fractional health decay
  @Builder.Default
  private float decayAccumulator = 0f;

  // Added deploy fields
  @Builder.Default
  private final float deployTime = DEFAULT_DEPLOY_TIME;

  @Builder.Default
  private float deployTimer = 1.0f;

  @Override
  public EntityType getEntityType() {
    return EntityType.BUILDING;
  }

  // Used for testing
  public boolean isDeploying() {
    return deployTimer > 0;
  }

  public boolean hasLifetime() {
    return lifetime > 0;
  }

  public boolean isExpired() {
    return hasLifetime() && remainingLifetime <= 0;
  }

  @Override
  public void onSpawn() {
    super.onSpawn();
    if (remainingLifetime == 0 && lifetime > 0) {
      remainingLifetime = lifetime;
    }
    // Sync deploy timer with deploy time if not manually set
    if (deployTimer == 0 && deployTime > 0) {
      deployTimer = deployTime;
    }
    if (deployTime <= 0) {
      deployTimer = 0;
      spawned = true; // Instant spawn
    }
  }

  @Override
  public void update(float deltaTime) {
    if (dead) {
      return;
    }

    // Handle deploy timer
    // While deploying, buildings are targetable but do not decay or attack
    if (deployTimer > 0) {
      deployTimer -= deltaTime;
      if (deployTimer <= 0) {
        deployTimer = 0;
        spawned = true;
      }
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
