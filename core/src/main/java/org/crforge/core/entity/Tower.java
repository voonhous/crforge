package org.crforge.core.entity;

import lombok.Getter;
import org.crforge.core.component.Combat;
import org.crforge.core.player.Team;

@Getter
public class Tower extends Building {

  private final TowerType towerType;
  private Entity currentTarget;

  private Tower(Builder builder) {
    super(builder);
    this.towerType = builder.towerType;
    this.currentTarget = null;
  }

  public static Builder builder() {
    return new Builder();
  }

  // Factory methods for standard towers
  public static Tower createCrownTower(Team team, float x, float y) {
    return Tower.builder()
        .name("Crown Tower")
        .team(team)
        .position(x, y)
        .maxHealth(4824)
        .size(4.0f)
        .towerType(TowerType.CROWN)
        .combat(
            Combat.builder()
                .damage(109)
                .range(6.5f)
                .sightRange(9.5f)
                .attackCooldown(0.8f)
                .ranged(true)
                .build())
        .build();
  }

  public static Tower createPrincessTower(Team team, float x, float y) {
    return Tower.builder()
        .name("Princess Tower")
        .team(team)
        .position(x, y)
        .maxHealth(3052)
        .size(3.0f)
        .towerType(TowerType.PRINCESS)
        .combat(
            Combat.builder()
                .damage(109)
                .range(7.5f)
                .sightRange(9.5f)
                .attackCooldown(0.8f)
                .ranged(true)
                .build())
        .build();
  }

  @Override
  public EntityType getEntityType() {
    return EntityType.TOWER;
  }

  public boolean isCrownTower() {
    return towerType == TowerType.CROWN;
  }

  public boolean isPrincessTower() {
    return towerType == TowerType.PRINCESS;
  }

  public Entity getCurrentTarget() {
    return currentTarget;
  }

  public void setCurrentTarget(Entity target) {
    this.currentTarget = target;
  }

  public boolean hasTarget() {
    return currentTarget != null && currentTarget.isAlive();
  }

  public void clearTarget() {
    this.currentTarget = null;
  }

  public enum TowerType {
    CROWN,
    PRINCESS
  }

  public static class Builder extends Building.Builder {

    private TowerType towerType = TowerType.PRINCESS;

    @Override
    public Builder name(String name) {
      super.name(name);
      return this;
    }

    @Override
    public Builder team(Team team) {
      super.team(team);
      return this;
    }

    @Override
    public Builder position(float x, float y) {
      super.position(x, y);
      return this;
    }

    @Override
    public Builder maxHealth(int maxHealth) {
      super.maxHealth(maxHealth);
      return this;
    }

    @Override
    public Builder size(float size) {
      super.size(size);
      return this;
    }

    @Override
    public Builder combat(Combat combat) {
      super.combat(combat);
      return this;
    }

    public Builder towerType(TowerType towerType) {
      this.towerType = towerType;
      return this;
    }

    @Override
    public Tower build() {
      return new Tower(this);
    }
  }
}