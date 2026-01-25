package org.crforge.core.effect;

/**
 * Defines the types of status effects available in CRForge. These types determine how the
 * StatusEffectSystem modifies entity attributes.
 */
public enum StatusEffectType {
  /**
   * Prevents both movement and attacking. Typically short duration (e.g., Zap).
   */
  STUN,

  /**
   * Reduces movement and attack speed by a specified intensity.
   */
  SLOW,

  /**
   * Increases movement and attack speed by a specified intensity.
   */
  RAGE,

  /**
   * A total halt of logic and physics. Prevents movement, attacking, and health regeneration.
   */
  FREEZE,

  /**
   * Damage over time (DOT) effect.
   */
  BURN,

  /**
   * Forced displacement away from a source. Prevents action while in motion.
   */
  KNOCKBACK,

  /**
   * Specific slow effect that also prevents invisibility or special abilities.
   */
  POISON,

  /**
   * Increases damage taken by the entity.
   */
  VULNERABILITY
}
