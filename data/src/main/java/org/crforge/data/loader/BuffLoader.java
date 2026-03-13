package org.crforge.data.loader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.data.loader.dto.BuffConfigDTO;

/** Loads buff definitions from buffs.json and converts them to BuffDefinition objects. */
public class BuffLoader {

  private static final ObjectMapper mapper = new ObjectMapper();

  /**
   * Loads buff definitions from the given input stream (JSON map of name -> BuffConfigDTO).
   *
   * @return map of buff name to BuffDefinition
   */
  public static Map<String, BuffDefinition> loadBuffs(InputStream inputStream) {
    try {
      Map<String, BuffConfigDTO> dtos = mapper.readValue(inputStream, new TypeReference<>() {});

      Map<String, BuffDefinition> result = new LinkedHashMap<>();
      for (Map.Entry<String, BuffConfigDTO> entry : dtos.entrySet()) {
        result.put(entry.getKey(), convert(entry.getValue()));
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load buffs from JSON", e);
    }
  }

  private static BuffDefinition convert(BuffConfigDTO dto) {
    return BuffDefinition.builder()
        .name(dto.getName())
        .speedMultiplier(dto.getSpeedMultiplier())
        .hitSpeedMultiplier(dto.getHitSpeedMultiplier())
        .spawnSpeedMultiplier(dto.getSpawnSpeedMultiplier())
        .damagePerSecond(dto.getDamagePerSecond())
        .healPerSecond(dto.getHealPerSecond())
        .crownTowerDamagePercent(dto.getCrownTowerDamagePercent())
        .buildingDamagePercent(dto.getBuildingDamagePercent())
        .hitFrequency(dto.getHitFrequency())
        .enableStacking(dto.isEnableStacking())
        .invisible(dto.isInvisible())
        .attractPercentage(dto.getAttractPercentage())
        .damageReduction(dto.getDamageReduction())
        .noEffectToCrownTowers(dto.isNoEffectToCrownTowers())
        .deathSpawnCount(dto.getDeathSpawnCount())
        .deathSpawnIsEnemy(dto.isDeathSpawnIsEnemy())
        .deathSpawn(dto.getDeathSpawn())
        .hitTickFromSource(dto.isHitTickFromSource())
        .build();
  }
}
