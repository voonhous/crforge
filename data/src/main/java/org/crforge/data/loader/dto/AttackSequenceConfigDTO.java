package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttackSequenceConfigDTO {

  private List<AttackSequenceHitConfigDTO> hits;
  private String mode;
}
