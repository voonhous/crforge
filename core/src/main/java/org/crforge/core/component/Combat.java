package org.crforge.core.component;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.entity.TargetType;

@Getter
@Builder
public class Combat {

  @Builder.Default
  private final int damage = 0;
  @Builder.Default
  private final float range = 1.0f;
  @Builder.Default
  private final float sightRange = 5.5f;
  @Builder.Default
  private final float attackCooldown = 1.0f;
  @Builder.Default
  private final float aoeRadius = 0;
  @Builder.Default
  private final TargetType targetType = TargetType.ALL;
  @Builder.Default
  private final boolean ranged = false;
  @Builder.Default
  private final float loadTime = 0;

  private float currentCooldown;
  private float currentLoadTime;

  public boolean canAttack() {
    return currentCooldown <= 0;
  }

  public boolean isLoading() {
    return currentLoadTime > 0;
  }

  public void startAttack() {
    currentLoadTime = loadTime;
  }

  public void finishAttack() {
    currentCooldown = attackCooldown;
    currentLoadTime = 0;
  }

  public void update(float deltaTime) {
    if (currentCooldown > 0) {
      currentCooldown -= deltaTime;
    }
    if (currentLoadTime > 0) {
      currentLoadTime -= deltaTime;
    }
  }

  public void resetCooldown() {
    currentCooldown = attackCooldown;
  }
}
