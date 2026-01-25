package org.crforge.core.entity;

import lombok.Getter;
import org.crforge.core.component.Combat;
import org.crforge.core.player.Team;

@Getter
public class Building extends AbstractEntity {

  private final Combat combat;
  private final float lifetime;
  private float remainingLifetime;

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

  @Override
  public void update(float deltaTime) {
    if (dead) {
      return;
    }

    // Reduce lifetime
    if (hasLifetime()) {
      remainingLifetime -= deltaTime;
      if (remainingLifetime <= 0) {
        remainingLifetime = 0;
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