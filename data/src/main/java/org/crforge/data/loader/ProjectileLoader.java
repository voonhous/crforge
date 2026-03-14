package org.crforge.data.loader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.data.loader.dto.ProjectileConfigDTO;

/**
 * Loads projectile definitions from projectiles.json and converts them to ProjectileStats. Handles
 * resolution of spawnProjectile string references within the same projectile map.
 */
public class ProjectileLoader {

  private static final ObjectMapper mapper =
      new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
  private static final float SPEED_BASE = 60.0f;

  /**
   * Loads all projectile definitions from the given input stream. Two-pass loading: first parse all
   * DTOs, then convert with spawnProjectile resolution.
   *
   * @param inputStream JSON input in Map(String, ProjectileConfigDTO) format
   * @return map of projectile name to ProjectileStats
   */
  public static Map<String, ProjectileStats> loadProjectiles(InputStream inputStream) {
    try {
      Map<String, ProjectileConfigDTO> dtos =
          mapper.readValue(inputStream, new TypeReference<>() {});

      // Pass 1: Convert all projectiles without spawnProjectile resolution
      Map<String, ProjectileStats> result = new LinkedHashMap<>();
      for (Map.Entry<String, ProjectileConfigDTO> entry : dtos.entrySet()) {
        result.put(entry.getKey(), convertProjectile(entry.getValue(), null));
      }

      // Pass 2: Resolve spawnProjectile references
      for (Map.Entry<String, ProjectileConfigDTO> entry : dtos.entrySet()) {
        ProjectileConfigDTO dto = entry.getValue();
        if (dto.getSpawnProjectile() != null) {
          // Re-convert with the resolved spawnProjectile
          result.put(entry.getKey(), convertProjectile(dto, result));
        }
      }

      return result;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load projectiles from JSON", e);
    }
  }

  /**
   * Converts a single ProjectileConfigDTO to ProjectileStats.
   *
   * @param dto the DTO to convert
   * @param projectileMap map for resolving spawnProjectile references (null on first pass)
   * @return the converted ProjectileStats
   */
  static ProjectileStats convertProjectile(
      ProjectileConfigDTO dto, Map<String, ProjectileStats> projectileMap) {
    float effectiveSpeed = dto.getSpeed() / SPEED_BASE;

    // Merge targetBuff into hitEffects as a post-damage effect
    List<EffectStats> hitEffects = new ArrayList<>();
    StatusEffectType buffType = StatusEffectType.fromBuffName(dto.getTargetBuff());
    if (buffType != null) {
      hitEffects.add(
          EffectStats.builder()
              .type(buffType)
              .duration(dto.getBuffDuration())
              .buffName(dto.getTargetBuff())
              .applyAfterDamage(true)
              .build());
    }

    ProjectileStats.ProjectileStatsBuilder builder =
        ProjectileStats.builder()
            .name(dto.getName())
            .damage(dto.getDamage())
            .speed(effectiveSpeed)
            .radius(dto.getRadius())
            .homing(dto.getHoming() != null ? dto.getHoming() : true)
            .hitEffects(hitEffects)
            .aoeToAir(dto.isAoeToAir())
            .aoeToGround(dto.isAoeToGround())
            .chainedHitRadius(dto.getChainedHitRadius())
            .chainedHitCount(dto.getChainedHitCount())
            .projectileRange(dto.getProjectileRange())
            .scatter(dto.getScatter())
            // Own spawnCount/spawnRadius (e.g. FirecrackerExplosion has these directly)
            .spawnCount(dto.getSpawnCount())
            .spawnRadius(dto.getSpawnRadius())
            .returning(dto.isPingpong() || dto.isReturning())
            .pingpongMovingShooter(dto.getPingpongMovingShooter())
            .pushback(dto.getPushback() / 1000f)
            .pushbackAll(dto.isPushbackAll())
            .checkCollisions(dto.isCheckCollisions())
            .crownTowerDamagePercent(dto.getCrownTowerDamagePercent())
            .spawnAreaEffect(CardLoader.convertAreaEffect(dto.getSpawnAreaEffect()));

    // Read spawn character from the spawn block (e.g. PhoenixFireball spawns PhoenixEgg)
    if (dto.getSpawn() != null && dto.getSpawn().getSpawnCharacter() != null) {
      builder.spawnCharacterName(dto.getSpawn().getSpawnCharacter());
      builder.spawnCharacterCount(
          dto.getSpawn().getSpawnNumber() > 0 ? dto.getSpawn().getSpawnNumber() : 1);
      builder.spawnDeployTime(dto.getSpawn().getDeployTime());
    }

    // Resolve spawnProjectile string reference
    if (dto.getSpawnProjectile() != null && projectileMap != null) {
      ProjectileStats spawnProj = projectileMap.get(dto.getSpawnProjectile());
      if (spawnProj != null) {
        builder.spawnProjectile(spawnProj);
        // Copy child's spawnCount/spawnRadius to parent if parent doesn't have its own
        if (dto.getSpawnCount() == 0 && spawnProj.getSpawnCount() > 0) {
          builder.spawnCount(spawnProj.getSpawnCount());
        }
        if (dto.getSpawnRadius() == 0 && spawnProj.getSpawnRadius() > 0) {
          builder.spawnRadius(spawnProj.getSpawnRadius());
        }
      }
    }

    return builder.build();
  }
}
