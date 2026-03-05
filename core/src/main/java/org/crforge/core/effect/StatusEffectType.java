package org.crforge.core.effect;

import java.util.Map;

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
  VULNERABILITY,

  /**
   * Entities that die while cursed spawn a specific unit (e.g. Cursed Hog) for the opponent.
   */
  CURSE,

  /**
   * Earthquake effect -- deals increased damage to buildings.
   */
  EARTHQUAKE,

  /**
   * Tornado pull -- drags entities toward the center of the effect area.
   */
  TORNADO;

  /**
   * Maps parsed buff names from cards.json to StatusEffectType values. Unknown buff names return
   * null for graceful degradation.
   */
  private static final Map<String, StatusEffectType> BUFF_NAME_MAP = Map.ofEntries(
      Map.entry("ZapFreeze", STUN),
      Map.entry("IceWizardSlowDown", SLOW),
      Map.entry("IceWizardCold", SLOW),
      Map.entry("Freeze", FREEZE),
      Map.entry("Poison", POISON),
      Map.entry("Rage", RAGE),
      Map.entry("VoodooCurse", CURSE),
      Map.entry("Earthquake", EARTHQUAKE),
      Map.entry("Tornado", TORNADO)
  );

  /**
   * Resolves a buff name from the parsed card data to a StatusEffectType.
   *
   * @param buffName the buff name string (e.g. "ZapFreeze", "IceWizardSlowDown")
   * @return the corresponding StatusEffectType, or null if the name is unknown
   */
  public static StatusEffectType fromBuffName(String buffName) {
    if (buffName == null) {
      return null;
    }
    return BUFF_NAME_MAP.get(buffName);
  }
}
