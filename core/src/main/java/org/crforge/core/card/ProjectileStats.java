package org.crforge.core.card;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.effect.StatusEffectType;

/**
 * Defines the configuration for a projectile. Used by Troops (ranged attacks) and Spells (e.g.
 * Fireball).
 */
@Getter
@Builder
public class ProjectileStats {

  private final String name;
  @Builder.Default
  private final int damage = 0;
  @Builder.Default
  private final float speed = 15.0f;
  @Builder.Default
  private final float radius = 0f; // AOE radius
  @Builder.Default
  private final boolean homing = true;
  @Builder.Default
  private final List<EffectStats> hitEffects = new ArrayList<>();

  /**
   * Status effect applied to the target on hit (e.g. SLOW for Ice Wizard). Null if none.
   */
  private final StatusEffectType targetBuff;

  /**
   * Duration of the targetBuff in seconds.
   */
  @Builder.Default
  private final float buffDuration = 0f;
}
