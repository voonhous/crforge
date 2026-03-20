package org.crforge.core.card;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** Represents the static configuration and definition of a Card. */
@Getter
@Builder(toBuilder = true)
public class Card {

  private final String id;
  private final String name;
  private final String description;
  private final CardType type;
  private final int cost;
  @Builder.Default private final Rarity rarity = Rarity.COMMON;

  /**
   * The unit type this card spawns (null for pure spells without a projectile-spawned character).
   */
  private final TroopStats unitStats;

  /**
   * How many copies of the unit to deploy (default 1). For example, Archers = 2, Barbarians = 5.
   */
  @Builder.Default private final int unitCount = 1;

  private final ProjectileStats projectile;

  /** Area effect for spells (e.g. Zap, Freeze, Poison). Null for non-spell cards. */
  private final AreaEffectStats areaEffect;

  /** Deploy effect triggered when the card enters the arena (e.g. ElectroWizard stun). */
  private final AreaEffectStats deployEffect;

  /**
   * Raw CSV summonRadius for troop deploy formation (divide by TILE_SCALE at deploy time). When 0,
   * uses default circular formation layout.
   */
  @Builder.Default private final float summonRadius = 0f;

  /** Stagger delay between each unit spawn for multi-unit cards (seconds). Zero = all at once. */
  @Builder.Default private final float summonDeployDelay = 0f;

  /**
   * Resolved spawn template stats. For spawner cards, this holds the TroopStats for the spawned
   * unit (resolved from liveSpawn.spawnCharacter).
   */
  private final TroopStats spawnTemplate;

  /** Resolved summon template for spells that summon a character (e.g. Rage -> RageBottle). */
  private final TroopStats summonTemplate;

  /**
   * Tunnel dig unit for buildings that deploy via underground travel (e.g. GoblinDrill uses
   * GoblinDrillDig). The dig unit tunnels from the king tower to the target, then morphs into the
   * building.
   */
  private final TroopStats tunnelDigUnit;

  /**
   * Projectile spawned at the deploy location when the card enters the arena (e.g. MegaKnight
   * landing damage). Null for most cards.
   */
  private final ProjectileStats spawnProjectile;

  /**
   * Pre-computed formation offsets in tile units. Each float[] is [x, y]. Null = use circular
   * algorithm.
   */
  private final List<float[]> formationOffsets;

  /**
   * Secondary unit type for dual-unit cards (e.g., SpearGoblin in GoblinGang). Null for single-unit
   * cards.
   */
  private final TroopStats secondaryUnitStats;

  /** How many secondary units to deploy (default 0). */
  @Builder.Default private final int secondaryUnitCount = 0;

  /**
   * Whether this card can be deployed on the enemy's side of the arena (e.g. Miner, GoblinDrill).
   */
  @Builder.Default private final boolean canDeployOnEnemySide = false;

  /** Spell placement/targeting radius in tile units (distinct from projectile AOE hit radius). */
  @Builder.Default private final float spellRadius = 0f;

  /** Number of projectiles per wave (cosmetic for simulation; stored for future visual use). */
  @Builder.Default private final int multipleProjectiles = 0;

  /** Number of projectile waves (e.g. Arrows fires 3 waves). */
  @Builder.Default private final int projectileWaves = 0;

  /** Interval between projectile waves in seconds (e.g. 0.2s for Arrows). */
  @Builder.Default private final float projectileWaveInterval = 0f;

  /** Spell deploys at placement location instead of traveling from behind (e.g. Log). */
  @Builder.Default private final boolean spellAsDeploy = false;

  /** Whether this card is the Mirror spell (replays last card at +1 level/cost). */
  @Builder.Default private final boolean mirror = false;

  /** Whether this spell only targets enemy entities (from parsed data). */
  @Builder.Default private final boolean onlyEnemies = false;

  /** Whether this spell can be placed at a location where a building entity exists. */
  @Builder.Default private final boolean canPlaceOnBuildings = true;

  /** Variant definitions for dual-form cards (e.g. MergeMaiden mounted/normal). */
  private final List<CardVariant> variants;

  /** Whether Mirror should replay the resolved variant instead of re-evaluating triggers. */
  @Builder.Default private final boolean mirrorCopiesVariant = false;

  public boolean isMirror() {
    return mirror;
  }

  public int getTotalDeployCount() {
    return unitCount + secondaryUnitCount;
  }

  public boolean isTroop() {
    return type == CardType.TROOP;
  }

  public boolean isSpell() {
    return type == CardType.SPELL;
  }

  public boolean isBuilding() {
    return type == CardType.BUILDING;
  }

  /** Returns true if this card has variant definitions that need elixir-based resolution. */
  public boolean hasVariants() {
    return variants != null && !variants.isEmpty();
  }

  /**
   * Returns a resolved Card with the matching variant's unitStats and cost. Variants are checked in
   * order; the first variant whose manaTrigger is <= currentElixir is selected. For non-variant
   * cards, returns this.
   *
   * @param currentElixir the player's elixir floor before spending
   * @return a new Card with the variant's stats, or this if no variants
   */
  public Card resolveVariant(int currentElixir) {
    if (variants == null || variants.isEmpty()) {
      return this;
    }
    for (CardVariant v : variants) {
      if (currentElixir >= v.manaTrigger()) {
        return this.toBuilder()
            .unitStats(v.unitStats())
            .unitCount(1)
            .cost(v.cost())
            .variants(null)
            .build();
      }
    }
    // Fallback: should not happen if variants are well-defined, but return this
    return this;
  }
}
