package org.crfoge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbilityConfigDTO {

  private String type;

  // CHARGE fields
  private int damage;
  private float triggerRange;
  private float speedMultiplier;

  // VARIABLE_DAMAGE fields
  private List<VariableDamageStageDTO> stages;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class VariableDamageStageDTO {

    private int damage;
    private int timeMs;
  }
}
