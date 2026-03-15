package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
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

  /** If true, buffs applied by this area effect are cleaned up when the effect expires. */
  private boolean controlsBuff;

  /** If true, buff duration is capped to the area effect's remaining lifetime. */
  private boolean capBuffTimeToAreaEffectTime;

  /** If true, this effect clones friendly troops in the area (Clone spell). */
  private boolean clone;

  /** If true, only affects the caster's own troops (Clone). */
  private boolean onlyOwnTroops;

  /** If true, buildings are excluded from targeting (Clone). */
  private boolean ignoreBuildings;

  /** If true, only affects enemy entities (Tornado, Earthquake, etc.). */
  private boolean onlyEnemies;

  /** If true, can affect hidden buildings like Tesla (Earthquake, Freeze). */
  private boolean affectsHidden;

  /** Maximum number of targets to select (e.g. 3 for Vines). Zero for normal area targeting. */
  private int targetCount;

  /** Target selection strategy (e.g. "HighestCurrentHpIncludeShields"). Null for default. */
  private String targetSelectionMode;

  /** Delay in seconds before the first target is selected (e.g. 0.4s for Vines). */
  private float initialDelay;

  /** Per-target offset delays from initialDelay (e.g. [0.0, 0.05, 0.15] for Vines). */
  private List<Float> targetDelays;

  /** If true, air units hit by this effect are pulled to ground level. */
  private boolean airToGround;

  /** Duration in seconds that air-to-ground conversion lasts. */
  private float airToGroundDuration;

  /** Spawn timing configuration (delay before character spawns). Null if no spawn. */
  private SpawnTimingConfigDTO spawn;

  /** Character to spawn after the area effect (e.g. Royal Delivery -> DeliveryRecruit). */
  private SpawnConfigDTO projectileSpawn;
}
