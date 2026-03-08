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

  /**
   * Original buff name from parsed data (e.g. "ZapFreeze", "IceWizardCold").
   * Used to look up BuffDefinition for data-driven multiplier resolution.
   */
  private final String buffName;

  /**
   * Whether this effect should be applied after damage (true) or before damage (false).
   * Before-damage is the default, important for effects like Curse where dying from damage
   * should still trigger the curse. After-damage is used for targetBuff effects (e.g. Ice
   * Wizard slow, EWiz stun) that are applied post-hit.
   */
  @Builder.Default
  private final boolean applyAfterDamage = false;
}
