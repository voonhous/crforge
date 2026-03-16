package org.crforge.core.card;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

/**
 * Runtime configuration for an area-of-effect, used by spells (e.g. Zap, Freeze, Poison) and deploy
 * effects (e.g. ElectroWizard entry stun).
 *
 * <p>Loaded from {@code areaEffect} / {@code deployEffect} blocks in cards.json.
 */
@Getter
@Builder
public class AreaEffectStats {

  private final String name;
  @Builder.Default private final float radius = 0f;
  @Builder.Default private final float lifeDuration = 0f;
  @Builder.Default private final boolean hitsGround = true;
  @Builder.Default private final boolean hitsAir = true;

  /** Damage per hit. Zero if the effect only applies a buff. */
  @Builder.Default private final int damage = 0;

  /** Interval in seconds between damage/buff ticks. Zero for one-shot effects. */
  @Builder.Default private final float hitSpeed = 0f;

  /** Buff name from the parsed data (e.g. "ZapFreeze", "Poison"). Null if no buff. */
  private final String buff;

  /** Duration of the buff in seconds. */
  @Builder.Default private final float buffDuration = 0f;

  /** Damage modifier for crown towers (e.g. -70 means 30% damage). Zero if none. */
  @Builder.Default private final int crownTowerDamagePercent = 0;

  /** Knockback strength applied to enemies on hit (e.g. GoblinDrillDamage). Zero if none. */
  @Builder.Default private final float pushback = 0f;

  /** If true, each tick targets the single highest-HP enemy not yet hit (e.g. Lightning). */
  @Builder.Default private final boolean hitBiggestTargets = false;

  /** If true, buffs applied by this area effect are cleaned up when the effect expires. */
  @Builder.Default private final boolean controlsBuff = false;

  /** If true, buff duration is capped to the area effect's remaining lifetime. */
  @Builder.Default private final boolean capBuffTimeToAreaEffectTime = false;

  /** If true, this effect clones friendly troops in the area (Clone spell). */
  @Builder.Default private final boolean clone = false;

  /** If true, only affects the caster's own troops (Clone). */
  @Builder.Default private final boolean onlyOwnTroops = false;

  /** If true, buildings are excluded from targeting (Clone). */
  @Builder.Default private final boolean ignoreBuildings = false;

  /** If true, only affects enemy entities (Tornado, Earthquake, etc.). */
  @Builder.Default private final boolean onlyEnemies = false;

  /** If true, can affect hidden buildings like Tesla (Earthquake, Freeze). */
  @Builder.Default private final boolean affectsHidden = false;

  /** Delay in seconds before the spawned character appears (from area effect creation). */
  @Builder.Default private final float spawnInitialDelay = 0f;

  /** Deploy time for the spawned character (animation duration). */
  @Builder.Default private final float spawnDeployTime = 0f;

  /** Resolved TroopStats for the character to spawn. Null if no spawn. */
  @With private final TroopStats spawnCharacter;

  /** Resolved TroopStats for death-spawn when this AEO applies a CURSE buff. Null if no curse. */
  @With private final TroopStats curseSpawnStats;

  /** Number of characters to spawn. */
  @Builder.Default private final int spawnCount = 1;

  /**
   * Ordered spawn sequence entries with per-entry delays and relative positions. Used by Graveyard
   * to spawn 13 Skeletons at predefined offsets with staggered timing.
   */
  @Builder.Default private final List<SpawnSequenceEntry> spawnSequence = List.of();

  /** Maximum number of targets to select (e.g. 3 for Vines). Zero for normal area targeting. */
  @Builder.Default private final int targetCount = 0;

  /** Target selection strategy (e.g. "HighestCurrentHpIncludeShields"). Null for default. */
  private final String targetSelectionMode;

  /** Delay in seconds before the first target is selected (e.g. 0.4s for Vines). */
  @Builder.Default private final float initialDelay = 0f;

  /** Per-target offset delays from initialDelay. */
  @Builder.Default private final List<Float> targetDelays = List.of();

  /** If true, air units hit by this effect are pulled to ground level. */
  @Builder.Default private final boolean airToGround = false;

  /** Duration in seconds that air-to-ground conversion lasts. */
  @Builder.Default private final float airToGroundDuration = 0f;

  /** Delay in seconds before the first laser ball scan (DarkMagic). */
  @Builder.Default private final float firstHitDelay = 0f;

  /** Interval in seconds between laser ball scans (DarkMagic). Mapped from JSON hitFrequency. */
  @Builder.Default private final float scanInterval = 0f;

  /** Base damage tier definitions for the laser ball mechanic (DarkMagic). */
  @Builder.Default private final List<DamageTier> damageTiers = List.of();

  /**
   * Returns true if this is a dummy area effect that has no gameplay impact. Some units (e.g.
   * RageBarbarian/Lumberjack, SuspiciousBush) carry a deathAreaEffect in units.json purely as an
   * internal game engine trigger -- it has zero radius and/or cannot hit anything.
   */
  public boolean isDummy() {
    return radius == 0f
        || (!hitsGround
            && !hitsAir
            && targetCount <= 0
            && damageTiers.isEmpty()
            && spawnCharacter == null
            && spawnSequence.isEmpty());
  }

  /**
   * Computes the total number of laser ball scans that will occur during the effect's lifetime.
   * Returns 0 if this is not a laser ball effect.
   */
  public int computeTotalLaserScans() {
    if (scanInterval <= 0 || firstHitDelay >= lifeDuration) {
      return 0;
    }
    int count = 0;
    float t = firstHitDelay;
    while (t < lifeDuration) {
      count++;
      t += scanInterval;
    }
    return count;
  }
}
