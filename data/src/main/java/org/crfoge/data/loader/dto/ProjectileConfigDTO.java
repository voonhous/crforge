package org.crfoge.data.loader.dto;

import java.util.List;
import lombok.Data;

@Data
public class ProjectileConfigDTO {

  private String name;
  private int damage;
  private float speed;
  private float radius;
  private Boolean homing;
  private List<EffectConfigDTO> hitEffects;
}
