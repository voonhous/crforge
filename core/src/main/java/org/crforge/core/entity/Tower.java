package org.crforge.core.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.player.Team;

@Getter
@SuperBuilder
public class Tower extends Building {

  @Builder.Default
  private final TowerType towerType = TowerType.PRINCESS;

  // Factory methods for standard towers
  public static Tower createCrownTower(Team team, float x, float y) {
    return Tower.builder()
        .name("Crown Tower")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(4824))
        .movement(new Movement(0, 0, 4.0f, MovementType.BUILDING))
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
        .position(new Position(x, y))
        .health(new Health(3052))
        .movement(new Movement(0, 0, 3.0f, MovementType.BUILDING))
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

  public enum TowerType {
    CROWN,
    PRINCESS
  }
}
