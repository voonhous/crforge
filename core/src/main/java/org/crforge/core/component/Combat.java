package org.crforge.core.component;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.TargetType;

@Getter
@Builder
@Setter
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

  @Builder.Default
  private final List<EffectStats> hitEffects = new ArrayList<>();

  private final ProjectileStats projectileStats;

  // Dynamic states
  private Entity currentTarget;
  private boolean targetLocked;

  private float currentCooldown;
  private float currentLoadTime;

  @Builder.Default
  private float attackSpeedMultiplier = 1.0f;
  @Builder.Default
  private boolean combatDisabled = false;

  public boolean hasTarget() {
    return currentTarget != null && currentTarget.isAlive();
  }

  public void clearTarget() {
    this.currentTarget = null;
    this.targetLocked = false;
  }

  public boolean canAttack() {
    return !combatDisabled && currentCooldown <= 0;
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
    float effectiveDelta = deltaTime * (combatDisabled ? 0 : attackSpeedMultiplier);
    if (currentCooldown > 0) {
      currentCooldown -= effectiveDelta;
    }
    if (currentLoadTime > 0) {
      currentLoadTime -= effectiveDelta;
    }
  }

  public void resetCooldown() {
    currentCooldown = attackCooldown;
  }
}
