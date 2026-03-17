package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** DTO for individual buff entries within an area effect's {@code buffs[]} array in JSON data. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuffApplicationDTO {

  private String buff;
  private float buffDuration;
}
