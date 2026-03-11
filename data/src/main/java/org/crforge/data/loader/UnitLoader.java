package org.crforge.data.loader;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.crforge.core.ability.AbilityData;
import org.crforge.core.ability.AbilityType;
import org.crforge.core.ability.ChargeAbility;
import org.crforge.core.ability.DashAbility;
import org.crforge.core.ability.HookAbility;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.ability.VariableDamageStage;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.data.loader.dto.AbilityConfigDTO;
import org.crforge.data.loader.dto.BuffOnDamageConfigDTO;
import org.crforge.data.loader.dto.DeathDamageConfigDTO;
import org.crforge.data.loader.dto.DeathSpawnConfigDTO;
import org.crforge.data.loader.dto.LiveSpawnConfigDTO;
import org.crforge.data.loader.dto.UnitConfigDTO;

/**
 * Loads unit definitions from units.json and converts them to TroopStats.
 * Resolves projectile and death spawn references from external maps.
 */
public class UnitLoader {

  private static final ObjectMapper mapper = new ObjectMapper()
      .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
  private static final float SPEED_BASE = 60.0f;

  /**
   * Loads all unit definitions from the given input stream.
   * Two-pass loading:
   * <ol>
   *   <li>Convert all units without death spawn resolution</li>
   *   <li>Resolve death spawn character references from the pass-1 map</li>
   * </ol>
   *
   * @param inputStream   JSON input in Map(String, UnitConfigDTO) format
   * @param projectileMap resolved projectile map for projectile reference resolution
   * @return map of unit name to TroopStats
   */
  public static Map<String, TroopStats> loadUnits(InputStream inputStream,
      Map<String, ProjectileStats> projectileMap) {
    try {
      Map<String, UnitConfigDTO> dtos = mapper.readValue(inputStream,
          new TypeReference<>() {});

      // Pass 1: Convert all units without death spawn resolution
      Map<String, TroopStats> result = new LinkedHashMap<>();
      for (Map.Entry<String, UnitConfigDTO> entry : dtos.entrySet()) {
        result.put(entry.getKey(), convertUnit(entry.getValue(), projectileMap, null));
      }

      // Pass 2: Resolve death spawn references from the unit map
      for (Map.Entry<String, UnitConfigDTO> entry : dtos.entrySet()) {
        UnitConfigDTO dto = entry.getValue();
        if (dto.getDeathSpawn() != null && !dto.getDeathSpawn().isEmpty()) {
          result.put(entry.getKey(), convertUnit(dto, projectileMap, result));
        }
      }

      return result;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load units from JSON", e);
    }
  }

  /**
   * Converts a single UnitConfigDTO to TroopStats.
   *
   * @param dto           the DTO to convert
   * @param projectileMap map for resolving projectile string references
   * @param unitMap       map for resolving death spawn character references (null on first pass)
   * @return the converted TroopStats
   */
  static TroopStats convertUnit(UnitConfigDTO dto,
      Map<String, ProjectileStats> projectileMap,
      Map<String, TroopStats> unitMap) {
    // Validate required fields
    Objects.requireNonNull(dto.getMovementType(),
        "MovementType is required for unit: " + dto.getName());

    // Validation: Require TargetType if the unit has ANY attack capability
    boolean hasAttackCapability = dto.getDamage() > 0 || dto.getProjectile() != null;
    if (hasAttackCapability) {
      Objects.requireNonNull(dto.getTargetType(),
          "TargetType is required for attacking unit: " + dto.getName());
    }

    // Conversion: Base speed 60 = 1.0 tiles/sec
    float effectiveSpeed = dto.getSpeed() / SPEED_BASE;

    // Resolve radii
    float colRad = dto.getCollisionRadius() != null ? dto.getCollisionRadius() : 0.5f;
    float visRad = dto.getVisualRadius() != null ? dto.getVisualRadius() : colRad;

    TroopStats.TroopStatsBuilder builder = TroopStats.builder()
        .name(dto.getName())
        .health(dto.getHealth())
        .damage(dto.getDamage())
        .speed(effectiveSpeed)
        .mass(dto.getMass())
        .collisionRadius(colRad)
        .visualRadius(visRad)
        .range(dto.getRange())
        .sightRange(dto.getSightRange() > 0 ? dto.getSightRange() : 5.5f)
        .attackCooldown(dto.getAttackCooldown())
        .loadTime(dto.getLoadTime() != null ? dto.getLoadTime() : 0f)
        .aoeRadius(dto.getAreaDamageRadius())
        .movementType(dto.getMovementType())
        .targetType(dto.getTargetType())
        .deployTime(dto.getDeployTime() != null ? dto.getDeployTime() : DEFAULT_DEPLOY_TIME)
        // Shield
        .shieldHitpoints(dto.getShieldHitpoints())
        // Combat modifiers
        .multipleTargets(dto.getMultipleTargets())
        .multipleProjectiles(dto.getMultipleProjectiles())
        .selfAsAoeCenter(dto.isSelfAsAoeCenter())
        // Targeting and combat modifiers
        .targetOnlyBuildings(dto.isTargetOnlyBuildings())
        .minimumRange(dto.getMinimumRange())
        .crownTowerDamagePercent(dto.getCrownTowerDamagePercent())
        .ignorePushback(dto.isIgnorePushback())
        // Building lifetime
        .lifeTime(dto.getLifeTime());

    // Resolve projectile reference
    if (dto.getProjectile() != null && projectileMap != null) {
      ProjectileStats projStats = projectileMap.get(dto.getProjectile());
      if (projStats != null) {
        // If unit has damage but projectile has 0 damage, use unit damage as fallback
        if (projStats.getDamage() == 0 && dto.getDamage() > 0) {
          projStats = projStats.withDamage(dto.getDamage());
        }
        builder.projectile(projStats);
      }
    }

    // Death damage
    DeathDamageConfigDTO deathDmg = dto.getDeathDamage();
    if (deathDmg != null) {
      builder.deathDamage(deathDmg.getDamage());
      builder.deathDamageRadius(deathDmg.getRadius());
      builder.deathPushback(deathDmg.getPushback() / 1000f);
    }

    // Buff on damage (e.g. EWiz stun, Mother Witch curse)
    BuffOnDamageConfigDTO buffOnDmg = dto.getBuffOnDamage();
    if (buffOnDmg != null) {
      StatusEffectType buffType = StatusEffectType.fromBuffName(buffOnDmg.getBuff());
      if (buffType != null) {
        builder.buffOnDamage(EffectStats.builder()
            .type(buffType)
            .duration(buffOnDmg.getDuration())
            .buffName(buffOnDmg.getBuff())
            .build());
      }
    }

    // Death spawns: resolve character references from the unit map
    if (dto.getDeathSpawn() != null && unitMap != null) {
      List<DeathSpawnEntry> deathSpawns = new ArrayList<>();
      for (DeathSpawnConfigDTO ds : dto.getDeathSpawn()) {
        TroopStats resolved = unitMap.get(ds.getSpawnCharacter());
        if (resolved != null) {
          deathSpawns.add(new DeathSpawnEntry(resolved, ds.getSpawnNumber(), ds.getSpawnRadius()));
        }
      }
      builder.deathSpawns(deathSpawns);
    }

    // Live spawn configuration
    LiveSpawnConfigDTO liveSpawn = dto.getLiveSpawn();
    if (liveSpawn != null) {
      builder.liveSpawn(new LiveSpawnConfig(
          liveSpawn.getSpawnCharacter(),
          liveSpawn.getSpawnNumber() > 0 ? liveSpawn.getSpawnNumber() : 1,
          liveSpawn.getSpawnPauseTime(),
          liveSpawn.getSpawnInterval(),
          liveSpawn.getSpawnStartTime(),
          liveSpawn.getSpawnRadius()
      ));
    }

    // Abilities (Charge, Variable Damage, etc.)
    if (dto.getAbilities() != null && !dto.getAbilities().isEmpty()) {
      // Use the first ability (cards have at most one primary ability)
      AbilityConfigDTO abilityDto = dto.getAbilities().get(0);
      AbilityData ability = convertAbility(abilityDto);
      if (ability != null) {
        builder.ability(ability);
      }
    }

    return builder.build();
  }

  static AbilityData convertAbility(AbilityConfigDTO dto) {
    AbilityType type;
    try {
      type = AbilityType.valueOf(dto.getType());
    } catch (IllegalArgumentException e) {
      return null; // Unknown ability type, skip
    }

    return switch (type) {
      case CHARGE -> new ChargeAbility(
          dto.getDamage(),
          dto.getSpeedMultiplier() > 0 ? dto.getSpeedMultiplier() : 2.0f);
      case VARIABLE_DAMAGE -> {
        List<VariableDamageStage> stages = dto.getStages() != null
            ? dto.getStages().stream()
                .map(s -> new VariableDamageStage(s.getDamage(), s.getTimeMs() / 1000f))
                .toList()
            : List.of();
        yield new VariableDamageAbility(stages);
      }
      case DASH -> new DashAbility(
          dto.getDamage(),
          dto.getMinRange(),
          dto.getMaxRange(),
          dto.getRadius(),
          dto.getCooldown(),
          dto.getImmuneTimeMs() / 1000f,
          dto.getLandingTime(),
          dto.getConstantTime(),
          dto.getPushback());
      case HOOK -> new HookAbility(
          dto.getRange(),
          dto.getMinimumRange(),
          dto.getLoadTime(),
          dto.getDragBackSpeed(),
          dto.getDragSelfSpeed());
      case REFLECT -> new ReflectAbility(
          dto.getDamage(),
          dto.getRadius(),
          StatusEffectType.fromBuffName(dto.getBuff()),
          dto.getBuffDuration(),
          dto.getCrownTowerDamagePercent(),
          dto.getBuff());
    };
  }
}
