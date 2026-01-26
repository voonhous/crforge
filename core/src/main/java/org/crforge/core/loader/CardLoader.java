package org.crforge.core.loader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.loader.dto.CardConfigDTO;
import org.crforge.core.loader.dto.EffectConfigDTO;
import org.crforge.core.loader.dto.UnitConfigDTO;

public class CardLoader {

  private static final ObjectMapper mapper = new ObjectMapper();

  public static List<Card> loadCards(InputStream inputStream) {
    try {
      List<CardConfigDTO> configs = mapper.readValue(inputStream, new TypeReference<>() {
      });
      return configs.stream().map(CardLoader::convert).toList();
    } catch (IOException e) {
      throw new RuntimeException("Failed to load cards from JSON", e);
    }
  }

  private static Card convert(CardConfigDTO dto) {
    Card.CardBuilder builder = Card.builder()
        .id(dto.getId())
        .name(dto.getName())
        .description(dto.getDescription())
        .type(dto.getType())
        .cost(dto.getCost())
        .spellDamage(dto.getSpellDamage())
        .spellRadius(dto.getSpellRadius())
        .buildingHealth(dto.getBuildingHealth())
        .buildingLifetime(dto.getBuildingLifetime())
        .spawnInterval(dto.getSpawnInterval())
        .deathSpawnCount(dto.getDeathSpawnCount());

    if (dto.getSpellEffects() != null) {
      builder.spellEffects(convertEffects(dto.getSpellEffects()));
    }

    if (dto.getUnits() != null) {
      for (UnitConfigDTO unitDto : dto.getUnits()) {
        int count = unitDto.getCount() > 0 ? unitDto.getCount() : 1;
        // If config specifies count > 1 (e.g. Barbarians), we create multiple TroopStats
        // In a real file, specific offsets might be defined in separate UnitConfig entries,
        // but for simple "count", we might need logic to distribute them.
        // For now, let's assume the JSON explicitly lists units if they have distinct offsets,
        // OR the count simply duplicates the stats (offsets handled elsewhere or ignored for now).
        // EDIT: The CardRegistry logic had specific offsets. The JSON should probably define
        // the array of units explicitly for things like Barbarians to control offsets.
        // But for simplicity here, I will just add the stats 'count' times.
        TroopStats stats = convertUnit(unitDto);
        for (int i = 0; i < count; i++) {
          builder.troop(stats);
        }
      }
    }

    return builder.build();
  }

  private static TroopStats convertUnit(UnitConfigDTO dto) {
    return TroopStats.builder()
        .name(dto.getName())
        .health(dto.getHealth())
        .damage(dto.getDamage())
        .speed(dto.getSpeed())
        .mass(dto.getMass())
        .size(dto.getSize())
        .range(dto.getRange())
        .sightRange(dto.getSightRange() > 0 ? dto.getSightRange() : 5.5f) // Default
        .attackCooldown(dto.getAttackCooldown())
        .aoeRadius(dto.getAoeRadius())
        .movementType(dto.getMovementType())
        .targetType(dto.getTargetType())
        .ranged(dto.isRanged())
        .deployTime(dto.getDeployTime() > 0 ? dto.getDeployTime() : 1.0f)
        .offsetX(dto.getOffsetX())
        .offsetY(dto.getOffsetY())
        .hitEffects(convertEffects(dto.getHitEffects()))
        .build();
  }

  private static List<EffectStats> convertEffects(List<EffectConfigDTO> dtos) {
    if (dtos == null) {
      return new ArrayList<>();
    }
    return dtos.stream().map(dto -> EffectStats.builder()
        .type(dto.getType())
        .duration(dto.getDuration())
        .intensity(dto.getIntensity())
        .build()).toList();
  }
}
