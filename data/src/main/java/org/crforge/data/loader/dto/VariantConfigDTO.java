package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** DTO for variant card definitions (e.g. MergeMaiden mounted/normal forms). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VariantConfigDTO {

  private String name;
  private int cost;
  private int manaTrigger;
  private String summonCharacter;
}
