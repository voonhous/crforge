package org.crfoge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectileConfigDTO {

  private String name;
  private int damage;
  private float speed;
  private float radius;
  private Boolean homing;
  private List<EffectConfigDTO> hitEffects;

  /**
   * Buff name applied to the target on hit (e.g. "IceWizardSlowDown").
   */
  private String targetBuff;

  /**
   * Duration of the targetBuff in seconds.
   */
  private float buffDuration;
}
