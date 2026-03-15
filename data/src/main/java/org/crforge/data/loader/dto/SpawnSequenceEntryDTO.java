package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO for a single entry in a spawn sequence. Each entry specifies the delay (in seconds, from area
 * effect creation) and the position offset relative to the area effect center.
 *
 * <p>Used by Graveyard to define the ordered spawn pattern for its 13 Skeletons.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpawnSequenceEntryDTO {

  /** Delay in seconds from area effect creation before this spawn fires. */
  private float spawnDelay;

  /** X offset in tiles relative to the area effect center. */
  private float relativeX;

  /** Y offset in tiles relative to the area effect center. */
  private float relativeY;
}
