package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AbilityConfigDTO {

  private String type;

  // CHARGE fields
  private int damage;
  private float triggerRange;
  private float speedMultiplier;

  // VARIABLE_DAMAGE fields
  private List<VariableDamageStageDTO> stages;

  // DASH fields
  private float minRange;
  private float maxRange;
  private float radius;
  private float cooldown;
  private int immuneTimeMs;
  private float pushback;
  private float landingTime;
  private float constantTime;

  // HOOK fields
  private float range;
  private float minimumRange;
  private float loadTime;
  private int dragBackSpeed;
  private int dragSelfSpeed;
  private String targetBuff;
  private float buffDuration;

  // REFLECT fields
  private String buff;
  private int crownTowerDamagePercent;

  // BUFF_ALLY fields
  private int maxTargets;
  private float searchRange;
  private float actionDelay;
  private float buffDelay;
  private int addedDamage;
  private int addedCrownTowerDamage;
  private int attackAmount;
  private float persistAfterDeath;
  private List<DamageMultiplierConfigDTO> damageMultipliers;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class VariableDamageStageDTO {

    private int damage;
    private int timeMs;
  }
}
