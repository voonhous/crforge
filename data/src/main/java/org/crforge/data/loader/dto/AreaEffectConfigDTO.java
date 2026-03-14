package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO for the {@code areaEffect} and {@code deployEffect} blocks in the card JSON. Describes an
 * area-of-effect that persists for a duration, optionally ticking damage/buffs.
 *
 * <p>Examples: Zap (one-shot damage + stun), Poison (ticking damage over 8s), Freeze (damage +
 * freeze buff for 4s), ElectroWizard deploy (stun on entry).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AreaEffectConfigDTO {

  private String name;
  private float radius;
  private float lifeDuration;
  private boolean hitsGround;
  private boolean hitsAir;

  /** Damage per hit. Zero if the effect only applies a buff. */
  private int damage;

  /** Interval in seconds between damage/buff ticks. Zero for one-shot effects. */
  private float hitSpeed;

  /** Buff name to apply (e.g. "ZapFreeze", "Poison"). Null if no buff. */
  private String buff;

  /** Duration of the buff in seconds. */
  private float buffDuration;

  /** Damage modifier for crown towers (e.g. -70 means 30% damage). Zero if none. */
  private int crownTowerDamagePercent;

  /** If true, each tick targets the single highest-HP enemy not yet hit (e.g. Lightning). */
  private boolean hitBiggestTargets;
}
