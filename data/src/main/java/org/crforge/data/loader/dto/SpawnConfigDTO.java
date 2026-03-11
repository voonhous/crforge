package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** DTO for character-spawning configuration on projectiles (e.g. GoblinBarrel spawns Goblins). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpawnConfigDTO {

  private String spawnCharacter;
  private int spawnNumber;
  private float deployTime;
}
