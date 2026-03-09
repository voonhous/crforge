package org.crforge.core.card;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Represents the static configuration and definition of a Card.
 */
@Getter
@Builder
public class Card {

  private final String id;
  private final String name;
  private final String description;
  private final CardType type;
  private final int cost;
  @Builder.Default
  private final Rarity rarity = Rarity.COMMON;

  /**
   * The unit type this card spawns (null for pure spells without a projectile-spawned character).
   */
  private final TroopStats unitStats;

  /**
   * How many copies of the unit to deploy (default 1). For example, Archers = 2, Barbarians = 5.
   */
  @Builder.Default
  private final int unitCount = 1;

  private final ProjectileStats projectile;

  /**
   * Area effect for spells (e.g. Zap, Freeze, Poison). Null for non-spell cards.
   */
  private final AreaEffectStats areaEffect;

  /**
   * Deploy effect triggered when the card enters the arena (e.g. ElectroWizard stun).
   */
  private final AreaEffectStats deployEffect;

  /**
   * Raw CSV summonRadius for troop deploy formation (divide by TILE_SCALE at deploy time). When 0,
   * uses default circular formation layout.
   */
  @Builder.Default
  private final float summonRadius = 0f;

  /**
   * Resolved spawn template stats. For spawner cards, this holds the TroopStats for the spawned
   * unit (resolved from liveSpawn.spawnCharacter).
   */
  private final TroopStats spawnTemplate;

  /**
   * Resolved summon template for spells that summon a character (e.g. Rage -> RageBottle).
   */
  private final TroopStats summonTemplate;

  /**
   * Pre-computed formation offsets in tile units. Each float[] is [x, y]. Null = use circular
   * algorithm.
   */
  private final List<float[]> formationOffsets;

  /**
   * Secondary unit type for dual-unit cards (e.g., SpearGoblin in GoblinGang). Null for
   * single-unit cards.
   */
  private final TroopStats secondaryUnitStats;

  /**
   * How many secondary units to deploy (default 0).
   */
  @Builder.Default
  private final int secondaryUnitCount = 0;

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

}
