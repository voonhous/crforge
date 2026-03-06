package org.crforge.core.ability;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

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
}
