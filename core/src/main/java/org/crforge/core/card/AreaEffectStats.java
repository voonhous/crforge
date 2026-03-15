package org.crforge.core.card;

import lombok.Builder;
import lombok.Getter;

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
}
