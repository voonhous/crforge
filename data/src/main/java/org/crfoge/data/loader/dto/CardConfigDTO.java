package org.crfoge.data.loader.dto;

import java.util.List;
import lombok.Data;
import org.crforge.core.card.CardType;

@Data
public class CardConfigDTO {

  private String id;
  private String name;
  private String description;
  private CardType type;
  private int cost;

  // Troop specifics
  private List<UnitConfigDTO> units;

  // Projectile specifics (For Spells)
  private ProjectileConfigDTO projectile;

  // Building specifics
  private int buildingHealth;
  private float buildingLifetime;

  // Spawner specifics
  private float spawnInterval;
  private int deathSpawnCount;
}
