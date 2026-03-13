package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO for the {@code liveSpawn} block inside a unit definition. Describes periodic spawning
 * behaviour (e.g. Tombstone spawning Skeletons).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LiveSpawnConfigDTO {

  private String spawnCharacter;
  private int spawnNumber;
  private float spawnPauseTime;
  private float spawnInterval;
  private float spawnStartTime;
  private float spawnRadius;
  private boolean spawnAttach;

  // Spawn limit: stop spawning after this many units (0 = unlimited)
  private int spawnLimit;

  // Destroy parent entity when spawn limit is reached (e.g. PhoenixEgg self-destructs after
  // hatching)
  private boolean destroyAtLimit;
}
