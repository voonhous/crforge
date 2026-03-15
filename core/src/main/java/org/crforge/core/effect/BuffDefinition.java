package org.crforge.core.effect;

import lombok.Builder;
import lombok.Getter;

/**
 * Immutable data class representing a buff definition loaded from buffs.json. Contains raw
 * multiplier values and helper methods to convert them to float multipliers.
 *
 * <p>Multiplier interpretation:
 *
 * <ul>
 *   <li>Positive values: multiplier = value / 100.0 (Rage 130 -> 1.3x)
 *   <li>Negative values: multiplier = 1.0 - abs(value) / 100.0 (IceWizard -30 -> 0.7x)
 *   <li>Zero: no change (1.0x)
 * </ul>
 */
@Getter
@Builder
public class BuffDefinition {

  private final String name;

  /** Raw speed multiplier from CSV (e.g. 130 for Rage, -30 for IceWizard, -100 for Freeze). */
  @Builder.Default private final int speedMultiplier = 0;

  /** Raw hit speed multiplier from CSV. */
  @Builder.Default private final int hitSpeedMultiplier = 0;

  /** Raw spawn speed multiplier from CSV. */
  @Builder.Default private final int spawnSpeedMultiplier = 0;

  /** Damage per second for DOT effects (e.g. Poison 36, Earthquake 32). */
  @Builder.Default private final int damagePerSecond = 0;

  /** Heal per second for heal-over-time effects (e.g. HealSpiritBuff 157). */
  @Builder.Default private final int healPerSecond = 0;

  /** Crown tower damage percent (e.g. -75 for Poison means 25% damage to crown towers). */
  @Builder.Default private final int crownTowerDamagePercent = 0;

  /** Building damage percent bonus (e.g. 350 for Earthquake means 4.5x damage to buildings). */
  @Builder.Default private final int buildingDamagePercent = 0;

  /** Time between damage ticks in seconds. */
  @Builder.Default private final float hitFrequency = 0f;

  /** Whether multiple instances from different sources can stack. */
  @Builder.Default private final boolean enableStacking = false;

  /** Pull strength for Tornado. Formula: attractPercentage / (30 * mass) = tiles/sec. */
  @Builder.Default private final int attractPercentage = 0;

  /** Push speed factor (100 = normal). Used by Tornado. */
  @Builder.Default private final int pushSpeedFactor = 0;

  /** If true, this buff's duration is managed by the parent AreaEffect, not self-expired. */
  @Builder.Default private final boolean controlledByParent = false;

  // Future fields (not yet integrated into systems)
  @Builder.Default private final boolean invisible = false;
  @Builder.Default private final int damageReduction = 0;
  @Builder.Default private final boolean noEffectToCrownTowers = false;
  @Builder.Default private final int deathSpawnCount = 0;
  @Builder.Default private final boolean deathSpawnIsEnemy = false;
  private final String deathSpawn;
  @Builder.Default private final boolean hitTickFromSource = false;

  /** Absolute per-tick crown tower damage (e.g. Vines 15 base). Overrides DPS-based calculation. */
  @Builder.Default private final int crownTowerDamagePerHit = 0;

  /**
   * Converts the raw speedMultiplier to a float multiplier for movement speed. Returns 1.0 if no
   * speed modification.
   */
  public float computeSpeedMultiplier() {
    return convertRawMultiplier(speedMultiplier);
  }

  /**
   * Converts the raw hitSpeedMultiplier to a float multiplier for attack speed. Returns 1.0 if no
   * hit speed modification.
   */
  public float computeHitSpeedMultiplier() {
    return convertRawMultiplier(hitSpeedMultiplier);
  }

  /**
   * Converts the raw spawnSpeedMultiplier to a float multiplier for spawn speed. Returns 1.0 if no
   * spawn speed modification.
   */
  public float computeSpawnSpeedMultiplier() {
    return convertRawMultiplier(spawnSpeedMultiplier);
  }

  /**
   * Converts a raw integer multiplier value to a float multiplier. Positive: value / 100.0 (e.g.
   * 130 -> 1.3) Negative: 1.0 - abs(value) / 100.0 (e.g. -30 -> 0.7, -100 -> 0.0) Zero: 1.0 (no
   * change)
   */
  private static float convertRawMultiplier(int raw) {
    if (raw == 0) {
      return 1.0f;
    }
    if (raw > 0) {
      return raw / 100.0f;
    }
    // Negative: 1.0 - abs(raw) / 100.0
    return 1.0f - Math.abs(raw) / 100.0f;
  }
}
