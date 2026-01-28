package org.crforge.core.card;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.effect.StatusEffectType;

/**
 * Defines the configuration for a status effect.
 * <p>
 * This acts as the blueprint for creating {@link org.crforge.core.effect.AppliedEffect} instances
 * at runtime. It defines <i>what</i> the effect does (Type, Intensity) and <i>how long</i> it lasts
 * initially (Duration).
 */
@Getter
@Builder
public class EffectStats {

  private final StatusEffectType type;

  /**
   * The duration of the effect in seconds.
   */
  private final float duration;

  /**
   * The magnitude of the effect (e.g., 0.35 for a 35% slow).
   */
  @Builder.Default
  private final float intensity = 0f;

  /**
   * For CURSE effects: Defines the unit that spawns when the cursed entity dies.
   */
  private final TroopStats spawnSpecies;
}
