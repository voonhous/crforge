package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** DTO for deserializing buff definitions from buffs.json. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuffConfigDTO {

  private String name;
  private int speedMultiplier;
  private int hitSpeedMultiplier;
  private int spawnSpeedMultiplier;
  private int damagePerSecond;
  private int healPerSecond;
  private int crownTowerDamagePercent;
  private int buildingDamagePercent;
  private float hitFrequency;
  private boolean enableStacking;

  // Future fields
  private boolean invisible;
  private int attractPercentage;
  private int damageReduction;
  private boolean noEffectToCrownTowers;
  private int deathSpawnCount;
  private boolean deathSpawnIsEnemy;
  private String deathSpawn;
  private boolean hitTickFromSource;
}
