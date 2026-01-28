package org.crfoge.data.loader;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crfoge.data.loader.dto.CardConfigDTO;
import org.crfoge.data.loader.dto.EffectConfigDTO;
import org.crfoge.data.loader.dto.ProjectileConfigDTO;
import org.crfoge.data.loader.dto.UnitConfigDTO;
import org.crforge.core.card.Card;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;

public class CardLoader {

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final float SPEED_BASE = 60.0f;

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
        .buildingHealth(dto.getBuildingHealth())
        .buildingLifetime(dto.getBuildingLifetime())
        .spawnInterval(dto.getSpawnInterval())
        .deathSpawnCount(dto.getDeathSpawnCount());

    if (dto.getProjectile() != null) {
      builder.projectile(convertProjectile(dto.getProjectile()));
    }

    if (dto.getUnits() != null) {
      for (UnitConfigDTO unitDto : dto.getUnits()) {
        int count = unitDto.getCount() > 0 ? unitDto.getCount() : 1;
        // If config specifies count > 1 (e.g. Barbarians), we create multiple TroopStats
        TroopStats stats = convertUnit(unitDto);
        for (int i = 0; i < count; i++) {
          builder.troop(stats);
        }
      }
    }

    return builder.build();
  }

  private static TroopStats convertUnit(UnitConfigDTO dto) {
    // Validate required fields
    Objects.requireNonNull(dto.getMovementType(),
        "MovementType is required for unit: " + dto.getName());

    if (dto.getDamage() > 0) {
      Objects.requireNonNull(dto.getTargetType(),
          "TargetType is required for attacking unit: " + dto.getName());
    }

    // Conversion: Base speed 60 = 1.0 tiles/sec
    float effectiveSpeed = dto.getSpeed() / SPEED_BASE;

    TroopStats.TroopStatsBuilder builder = TroopStats.builder()
        .name(dto.getName())
        .health(dto.getHealth())
        .damage(dto.getDamage())
        .speed(effectiveSpeed)
        .mass(dto.getMass())
        .size(dto.getSize())
        .range(dto.getRange())
        .sightRange(dto.getSightRange() > 0 ? dto.getSightRange() : 5.5f) // Default
        .attackCooldown(dto.getAttackCooldown())
        .aoeRadius(dto.getAoeRadius())
        .movementType(dto.getMovementType())
        .targetType(dto.getTargetType())
        .ranged(dto.isRanged())
        .deployTime(dto.getDeployTime() != null ? dto.getDeployTime() : DEFAULT_DEPLOY_TIME)
        .offsetX(dto.getOffsetX())
        .offsetY(dto.getOffsetY())
        .hitEffects(convertEffects(dto.getHitEffects()));

    if (dto.getProjectile() != null) {
      builder.projectile(convertProjectile(dto.getProjectile()));
    }

    return builder.build();
  }

  private static ProjectileStats convertProjectile(ProjectileConfigDTO dto) {
    if (dto == null) {
      return null;
    }

    float effectiveSpeed = dto.getSpeed() / SPEED_BASE;
    return ProjectileStats.builder()
        .name(dto.getName())
        .damage(dto.getDamage())
        .speed(effectiveSpeed)
        .radius(dto.getRadius())
        .homing(dto.getHoming() != null ? dto.getHoming() : true)
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
