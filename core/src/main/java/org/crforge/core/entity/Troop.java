package org.crforge.core.entity;

import lombok.Getter;
import lombok.Setter;
import org.crforge.core.component.Combat;
import org.crforge.core.player.Team;

@Getter
public class Troop extends AbstractEntity {

  private final Combat combat;
  private final float deployTime;

  @Setter private Entity currentTarget;
  @Setter private boolean targetLocked;
  private float deployTimer;

  private Troop(Builder builder) {
    super(
        builder.name,
        builder.team,
        builder.x,
        builder.y,
        builder.maxHealth,
        builder.speed,
        builder.mass,
        builder.size,
        builder.movementType);
    this.combat = builder.combat;
    this.deployTime = builder.deployTime;
    this.deployTimer = deployTime;
    this.currentTarget = null;
    this.targetLocked = false;
  }

  public static Builder builder() {
    return new Builder();
  }

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
    if (currentTarget == null) return false;
    float distance = position.distanceTo(currentTarget.getPosition());
    float effectiveRange = combat.getRange() + (getSize() + currentTarget.getSize()) / 2f;
    return distance <= effectiveRange;
  }

  public float getDistanceToTarget() {
    if (currentTarget == null) return Float.MAX_VALUE;
    return position.distanceTo(currentTarget.getPosition());
  }

  @Override
  public void update(float deltaTime) {
    if (dead) return;

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
    combat.update(deltaTime);
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
    return super.isTargetable() && !isDeploying();
  }

  public static class Builder {
    private String name = "Troop";
    private Team team = Team.BLUE;
    private float x = 0;
    private float y = 0;
    private int maxHealth = 100;
    private float speed = 1.0f;
    private float mass = 1.0f;
    private float size = 1.0f;
    private MovementType movementType = MovementType.GROUND;
    private Combat combat = Combat.builder().build();
    private float deployTime = 1.0f;

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

    public Builder speed(float speed) {
      this.speed = speed;
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

    public Builder movementType(MovementType movementType) {
      this.movementType = movementType;
      return this;
    }

    public Builder combat(Combat combat) {
      this.combat = combat;
      return this;
    }

    public Builder deployTime(float deployTime) {
      this.deployTime = deployTime;
      return this;
    }

    public Troop build() {
      return new Troop(this);
    }
  }
}
