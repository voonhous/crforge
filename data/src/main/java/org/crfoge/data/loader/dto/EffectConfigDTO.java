package org.crfoge.data.loader.dto;

import lombok.Data;
import org.crforge.core.effect.StatusEffectType;

@Data
public class EffectConfigDTO {

  private StatusEffectType type;
  private float duration;
  private float intensity;
}
