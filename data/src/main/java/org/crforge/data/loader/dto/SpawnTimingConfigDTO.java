package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO for the {@code spawn} timing sub-block in area effects. Contains delays that control when a
 * character spawn fires after the area effect is created.
 *
 * <p>Example: Royal Delivery has spawnInitialDelay=2.05s (recruit drops 0.05s after the 2.0s damage
 * tick).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpawnTimingConfigDTO {

  /** Delay in seconds before the spawn fires, measured from area effect creation. */
  private float spawnInitialDelay;

  /** Duration of the spawn animation in seconds. */
  private float spawnTime;
}
