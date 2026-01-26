package org.crforge.core.card;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

/**
 * Represents the static configuration and definition of a Card.
 * <p>
 * This class acts as a <b>Blueprint</b> (or Type Object). It contains the base stats, costs, and
 * spawn definitions used to create runtime entities. It is immutable and loaded from data files.
 * <p>
 * <b>Architecture Note:</b><br>
 * Unlike {@link org.crforge.core.entity.base.Entity}, which represents a specific unit on the
 * battlefield with mutable state (e.g., current health, current position), this class represents
 * the abstract definition of the card (e.g., max health, base speed).
 * <p>
 * Used by:
 * <ul>
 * <li>{@link org.crforge.core.player.Deck} - To define the player's available cards.</li>
 * <li>{@link org.crforge.core.engine.DeploymentSystem} - To instantiate new Entities when a card is played.</li>
 * </ul>
 */
@Getter
@Builder
public class Card {

  private final String id;
  private final String name;
  private final String description;
  private final CardType type;
  private final int cost;

  /**
   * Defines the units that this card spawns.
   * <p>
   * For example, a "Skeleton Army" card would have a list containing one {@link TroopStats}
   * definition for a Skeleton, but the {@link org.crforge.core.engine.DeploymentSystem} will use
   * that definition to spawn multiple Skeleton entities.
   */
  @Singular
  private final List<TroopStats> troops;

  // For spells
  @Builder.Default
  private final int spellDamage = 0;
  @Builder.Default
  private final float spellRadius = 0f;
  @Builder.Default
  private final List<EffectStats> spellEffects = new ArrayList<>();

  // For buildings
  @Builder.Default
  private final int buildingHealth = 0;
  @Builder.Default
  private final float buildingLifetime = 0f;

  // For spawners (Buildings or Troops)
  @Builder.Default
  private final float spawnInterval = 0f;
  @Builder.Default
  private final int deathSpawnCount = 0;

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
