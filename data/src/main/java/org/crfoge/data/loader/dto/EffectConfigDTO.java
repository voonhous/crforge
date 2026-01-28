package org.crfoge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.crforge.core.effect.StatusEffectType;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EffectConfigDTO {

  private StatusEffectType type;
  private float duration;
  private float intensity;

  private UnitConfigDTO spawnUnit;
}
