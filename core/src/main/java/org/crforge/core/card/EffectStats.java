package org.crforge.core.card;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.effect.StatusEffectType;

@Getter
@Builder
public class EffectStats {

  private final StatusEffectType type;
  private final float duration;
  @Builder.Default
  private final float intensity = 0f; // e.g. 0.35 for 35% slow
}
