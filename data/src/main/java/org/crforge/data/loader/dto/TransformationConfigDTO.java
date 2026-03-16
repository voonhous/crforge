package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** DTO for HP-threshold transformation data in units.json. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransformationConfigDTO {

  // Unit name reference into units.json (e.g. "GoblinDemolisher_kamikaze_form")
  private String transformCharacter;

  // HP threshold percentage (0-100) at which the transformation triggers
  private int healthPercent;
}
