package org.crfoge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuffOnDamageConfigDTO {

  private String buff;
  private float duration;
}
