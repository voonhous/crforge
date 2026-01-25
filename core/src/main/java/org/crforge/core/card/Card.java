package org.crforge.core.card;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

@Getter
@Builder
public class Card {

  private final String id;
  private final String name;
  private final String description;
  private final CardType type;
  private final int cost;

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
