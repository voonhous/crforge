package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StealthConfigDTO {

  private int hideTimeMs;
  private int notAttackingTimeMs;
  private String buff;
}
