package org.crforge.core.card;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

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
   * Defines the units that this card spawns.
   */
  @Singular
  private final List<TroopStats> troops;

  private final ProjectileStats projectile;

  /**
   * Area effect for spells (e.g. Zap, Freeze, Poison). Null for non-spell cards.
   */
  private final AreaEffectStats areaEffect;

  /**
   * Deploy effect triggered when the card enters the arena (e.g. ElectroWizard stun).
   */
  private final AreaEffectStats deployEffect;

  // For buildings
  @Builder.Default
  private final int buildingHealth = 0;
  @Builder.Default
  private final float buildingLifetime = 0f;

  // For spawners (Buildings or Troops)
  @Builder.Default
  private final float spawnInterval = 0f;
  @Builder.Default
  private final float spawnPauseTime = 0f;
  @Builder.Default
  private final int spawnNumber = 1; // Default to 1 unit per wave
  @Builder.Default
  private final int deathSpawnCount = 0;
  @Builder.Default
  private final float spawnStartTime = 0f;

  /**
   * Resolved spawn template stats. For spawner cards, this holds the TroopStats
   * for the spawned unit (resolved from liveSpawn.spawnCharacter or units[1]).
   */
  private final TroopStats spawnTemplate;

  /**
   * Resolved summon template for spells that summon a character (e.g. Rage -> RageBottle).
   */
  private final TroopStats summonTemplate;

  public boolean isTroop() {
    return type == CardType.TROOP;
  }

  public boolean isSpell() {
    return type == CardType.SPELL;
  }

  public boolean isBuilding() {
    return type == CardType.BUILDING;
  }

  public int getTroopCount() {
    return troops != null ? troops.size() : 0;
  }
}
