package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO for a single damage tier in the DarkMagic (Void) laser ball mechanic. Each tier defines a DPS
 * value and crown tower damage that applies when the target count falls within the tier's range.
 *
 * <p>Tiers are ordered by ascending maxTargets. A tier with maxTargets=0 (or absent) acts as the
 * catch-all fallback for any target count above the previous tier's cap.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DamageTierConfigDTO {

  /** Damage per second for this tier. Converted to per-tick damage at runtime. */
  private int damagePerSecond;

  /** Crown tower damage per 100ms tick. Zero if none. */
  private int crownTowerDamagePerHit;

  /** Maximum number of targets for this tier. Zero means catch-all (no cap). */
  private int maxTargets;
}
