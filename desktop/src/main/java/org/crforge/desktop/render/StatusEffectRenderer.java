package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.graphics.Color;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.Entity;

/**
 * Static utility for mapping status effects to colors.
 * Priority order determines which effect color is displayed when multiple effects are active.
 */
public final class StatusEffectRenderer {

  private StatusEffectRenderer() {}

  /** Priority-ordered list of effect types (highest priority first). */
  private static final List<StatusEffectType> PRIORITY_ORDER = List.of(
      StatusEffectType.FREEZE,
      StatusEffectType.STUN,
      StatusEffectType.KNOCKBACK,
      StatusEffectType.SLOW,
      StatusEffectType.POISON,
      StatusEffectType.BURN,
      StatusEffectType.EARTHQUAKE,
      StatusEffectType.TORNADO,
      StatusEffectType.VULNERABILITY,
      StatusEffectType.CURSE,
      StatusEffectType.RAGE
  );

  private static final Map<StatusEffectType, Color> EFFECT_COLORS;

  static {
    EFFECT_COLORS = new EnumMap<>(StatusEffectType.class);
    EFFECT_COLORS.put(StatusEffectType.FREEZE, COLOR_EFFECT_FREEZE);
    EFFECT_COLORS.put(StatusEffectType.STUN, COLOR_EFFECT_STUN);
    EFFECT_COLORS.put(StatusEffectType.KNOCKBACK, COLOR_EFFECT_KNOCKBACK);
    EFFECT_COLORS.put(StatusEffectType.SLOW, COLOR_EFFECT_SLOW);
    EFFECT_COLORS.put(StatusEffectType.POISON, COLOR_EFFECT_POISON);
    EFFECT_COLORS.put(StatusEffectType.BURN, COLOR_EFFECT_BURN);
    EFFECT_COLORS.put(StatusEffectType.EARTHQUAKE, COLOR_EFFECT_EARTHQUAKE);
    EFFECT_COLORS.put(StatusEffectType.TORNADO, COLOR_EFFECT_TORNADO);
    EFFECT_COLORS.put(StatusEffectType.VULNERABILITY, COLOR_EFFECT_VULNERABILITY);
    EFFECT_COLORS.put(StatusEffectType.CURSE, COLOR_EFFECT_CURSE);
    EFFECT_COLORS.put(StatusEffectType.RAGE, COLOR_EFFECT_RAGE);
  }

  /**
   * Returns the color for a specific status effect type, or null if unmapped.
   */
  public static Color getEffectColor(StatusEffectType type) {
    return EFFECT_COLORS.get(type);
  }

  /**
   * Returns the color for the highest-priority active status effect on the entity,
   * or null if no effects are active.
   */
  public static Color getStatusEffectColor(Entity entity) {
    if (entity.getAppliedEffects().isEmpty()) {
      return null;
    }

    // Collect active effect types
    boolean[] active = new boolean[StatusEffectType.values().length];
    for (AppliedEffect effect : entity.getAppliedEffects()) {
      active[effect.getType().ordinal()] = true;
    }

    // Return color for highest-priority active effect
    for (StatusEffectType type : PRIORITY_ORDER) {
      if (active[type.ordinal()]) {
        return EFFECT_COLORS.get(type);
      }
    }
    return null;
  }
}
