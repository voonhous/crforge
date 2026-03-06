package org.crfoge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO for parsing death damage configuration from parsed cards JSON.
 * Applied as AOE damage at the unit's position when it dies.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeathDamageConfigDTO {

  private int damage;
  private float radius;
}
