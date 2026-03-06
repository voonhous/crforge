package org.crforge.core.ability;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.effect.StatusEffectType;

/**
 * Immutable definition of an ability, loaded from cards.json.
 * Stored in TroopStats and copied to AbilityComponent at deployment.
 */
@Getter
@Builder
public class AbilityData {

  private final AbilityType type;

  // CHARGE fields
  @Builder.Default
  private final int chargeDamage = 0;
  @Builder.Default
  private final float speedMultiplier = 1.0f;

  // VARIABLE_DAMAGE fields
  @Builder.Default
  private final List<VariableDamageStage> stages = List.of();

  // DASH fields
  @Builder.Default
  private final int dashDamage = 0;
  @Builder.Default
  private final float dashMinRange = 0f;
  @Builder.Default
  private final float dashMaxRange = 0f;
  @Builder.Default
  private final float dashRadius = 0f;
  @Builder.Default
  private final float dashCooldown = 0f;
  @Builder.Default
  private final float dashImmuneTime = 0f;
  @Builder.Default
  private final float dashLandingTime = 0f;

  // HOOK fields
  @Builder.Default
  private final float hookRange = 0f;
  @Builder.Default
  private final float hookMinimumRange = 0f;
  @Builder.Default
  private final float hookLoadTime = 0f;
  @Builder.Default
  private final float hookDragBackSpeed = 0f;
  @Builder.Default
  private final float hookDragSelfSpeed = 0f;

  // REFLECT fields
  @Builder.Default
  private final int reflectDamage = 0;
  @Builder.Default
  private final float reflectRadius = 0f;
  @Builder.Default
  private final StatusEffectType reflectBuff = null;
  @Builder.Default
  private final float reflectBuffDuration = 0f;
  @Builder.Default
  private final int reflectCrownTowerDamagePercent = 0;
}
