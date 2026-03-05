package org.crfoge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO for entries in the {@code deathSpawn} array inside a unit definition.
 * Describes units spawned when the parent dies (e.g. Golem -> 2 Golemites).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeathSpawnConfigDTO {

  private String spawnCharacter;
  private int spawnNumber;
  private float spawnRadius;
}
