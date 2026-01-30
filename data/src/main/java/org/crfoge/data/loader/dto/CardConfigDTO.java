package org.crfoge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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

  @JsonProperty("lifeTime")
  private float buildingLifetime;

  // Spawner specifics
  private float spawnInterval;
  private float spawnPauseTime;
  private int spawnNumber;
  private int deathSpawnCount;
}
