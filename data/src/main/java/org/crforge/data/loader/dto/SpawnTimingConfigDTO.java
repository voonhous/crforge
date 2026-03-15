package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

/**
 * DTO for the {@code spawn} timing sub-block in area effects. Contains delays that control when a
 * character spawn fires after the area effect is created.
 *
 * <p>Example: Royal Delivery has spawnInitialDelay=2.05s (recruit drops 0.05s after the 2.0s damage
 * tick). Graveyard has a spawnSequence of 13 entries with per-skeleton delays and positions.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpawnTimingConfigDTO {

  /** Delay in seconds before the spawn fires, measured from area effect creation. */
  private float spawnInitialDelay;

  /** Duration of the spawn animation in seconds. */
  private float spawnTime;

  /** Unit name reference for the character to spawn (e.g. "Skeleton" for Graveyard). */
  private String spawnCharacter;

  /** Ordered spawn entries with per-entry delays and relative positions (e.g. Graveyard). */
  private List<SpawnSequenceEntryDTO> spawnSequence;
}
