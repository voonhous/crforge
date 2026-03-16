package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** DTO for damage multiplier entries in BUFF_ALLY ability configuration. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DamageMultiplierConfigDTO {

  private String name;

  /** Multiplier in hundredths: 500 = 5x, 100 = 1x, 0 = no bonus. */
  private int multiplier;
}
