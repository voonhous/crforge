package org.crforge.core.entity;

import lombok.Getter;
import lombok.Setter;
import org.crforge.core.component.Combat;
import org.crforge.core.player.Team;

@Getter
public class Building extends AbstractEntity {

  private final Combat combat;
  private final float lifetime;
  private float remainingLifetime;

  @Setter
  private Entity currentTarget;

  // Accumulator for fractional health decay
  private float decayAccumulator;

  protected Building(Builder builder) {
    super(
        builder.name,
        builder.team,
        builder.x,
        builder.y,
        builder.maxHealth,
        0,
        builder.mass,
        builder.size,
        MovementType.BUILDING);
    this.combat = builder.combat;
    this.lifetime = builder.lifetime;
    this.remainingLifetime = lifetime;
    this.currentTarget = null;
    this.decayAccumulator = 0f;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public EntityType getEntityType() {
    return EntityType.BUILDING;
  }

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

  public static class Builder {

    protected String name = "Building";
    protected Team team = Team.BLUE;
    protected float x = 0;
    protected float y = 0;
    protected int maxHealth = 500;
    protected float mass = 0;
    protected float size = 3.0f;
    protected Combat combat = null;
    protected float lifetime = 0; // 0 = permanent

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder team(Team team) {
      this.team = team;
      return this;
    }

    public Builder position(float x, float y) {
      this.x = x;
      this.y = y;
      return this;
    }

    public Builder maxHealth(int maxHealth) {
      this.maxHealth = maxHealth;
      return this;
    }

    public Builder mass(float mass) {
      this.mass = mass;
      return this;
    }

    public Builder size(float size) {
      this.size = size;
      return this;
    }

    public Builder combat(Combat combat) {
      this.combat = combat;
      return this;
    }

    public Builder lifetime(float lifetime) {
      this.lifetime = lifetime;
      return this;
    }

    public Building build() {
      return new Building(this);
    }
  }
}
