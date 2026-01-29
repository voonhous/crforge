package org.crforge.core.entity.structure;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.player.Team;

@Getter
@SuperBuilder
public class Tower extends Building {

  @Builder.Default
  private final TowerType towerType = TowerType.PRINCESS;

  /**
   * Towers can be active or inactive.
   * <p>
   * Crown (King) towers start inactive and activate when damaged or a Princess tower falls.
   */
  @Builder.Default
  @Setter
  private boolean active = true;

  /**
   * Timer that ticks down when a tower is first activated.
   * <p>
   * The tower cannot attack until this timer reaches 0.
   */
  @Builder.Default
  private float activationTimer = 0f;

  // Factory methods for standard towers
  public static Tower createCrownTower(Team team, float x, float y) {
    // Crown Tower: Collision Radius 1.4, Visual Size 4.0 (Radius 2.0)
    return Tower.builder()
        .name("Crown Tower")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(4824))
        .movement(new Movement(0, 0, 1.4f, 2.0f, MovementType.BUILDING))
        .towerType(TowerType.CROWN)
        .active(false)
        .combat(
            Combat.builder()
                .damage(109)
                .range(7.0f)
                .sightRange(7.0f)
                .attackCooldown(1.0f)
                .firstAttackCooldown(0.0f)
                .build())
        .build();
  }

  public static Tower createPrincessTower(Team team, float x, float y) {
    // Princess Tower: Collision Radius 1.0, Visual Size 3.0 (Radius 1.5)
    return Tower.builder()
        .name("Princess Tower")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(3052))
        .movement(new Movement(0, 0, 1.0f, 1.5f, MovementType.BUILDING))
        .towerType(TowerType.PRINCESS)
        .active(true)
        .combat(
            Combat.builder()
                .damage(109)
                .range(7.5f)
                .sightRange(7.5f)
                .attackCooldown(0.8f)
                .firstAttackCooldown(0.0f)
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

  public boolean isWakingUp() {
    return activationTimer > 0;
  }

  public void activate() {
    if (!active) {
      active = true;
      // 1 second activation delay
      activationTimer = 1.0f;
    }
  }


  @Override
  public void update(float deltaTime) {
    super.update(deltaTime);

    // Auto-activate if damaged
    if (!active && health.getCurrent() < health.getMax()) {
      activate();
    }

    if (activationTimer > 0) {
      activationTimer -= deltaTime;
    }
  }

  public enum TowerType {
    CROWN,
    PRINCESS
  }
}
