package org.crforge.core.entity.structure;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.card.LevelScaling;
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

  @Builder.Default private final TowerType towerType = TowerType.PRINCESS;

  @Builder.Default @Setter private boolean active = true;

  @Builder.Default @Setter private float activationTimer = 0f;

  // Tower geometry in game units (unchanged from the previous tile values: crown 1.4 / 2.0 radius,
  // 7.0 range; princess 1.0 / 1.5 radius, 7.5 range, 9.5 sight range)
  public static final int CROWN_COLLISION_RADIUS = 1400;
  public static final int CROWN_VISUAL_RADIUS = 2000;
  public static final int CROWN_RANGE = 7000;
  public static final int PRINCESS_COLLISION_RADIUS = 1000;
  public static final int PRINCESS_VISUAL_RADIUS = 1500;
  public static final int PRINCESS_RANGE = 7500;
  public static final int PRINCESS_SIGHT_RANGE = 9500;

  // Factory methods for standard towers
  public static Tower createCrownTower(Team team, int x, int y, int level) {
    return Tower.builder()
        .name("Crown Tower")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(LevelScaling.scaleKingHp(level)))
        .movement(
            new Movement(0, 0, CROWN_COLLISION_RADIUS, CROWN_VISUAL_RADIUS, MovementType.BUILDING))
        .towerType(TowerType.CROWN)
        .active(false)
        .level(level)
        .combat(
            Combat.builder()
                .damage(LevelScaling.scaleKingDamage(level))
                .range(CROWN_RANGE)
                .sightRange(CROWN_RANGE)
                .attackCooldown(1.0f)
                .loadTime(0.0f)
                .build())
        .build();
  }

  public static Tower createPrincessTower(Team team, int x, int y, int level) {
    return Tower.builder()
        .name("Princess Tower")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(LevelScaling.scalePrincessHp(level)))
        .movement(
            new Movement(
                0, 0, PRINCESS_COLLISION_RADIUS, PRINCESS_VISUAL_RADIUS, MovementType.BUILDING))
        .towerType(TowerType.PRINCESS)
        .active(true)
        .level(level)
        .combat(
            Combat.builder()
                .damage(LevelScaling.scalePrincessDamage(level))
                .range(PRINCESS_RANGE)
                .sightRange(PRINCESS_SIGHT_RANGE)
                .attackCooldown(0.8f)
                .loadTime(0.0f)
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
      activationTimer = 1.0f;
    }
  }

  public enum TowerType {
    CROWN,
    PRINCESS
  }
}
