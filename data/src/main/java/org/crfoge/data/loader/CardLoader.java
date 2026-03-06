package org.crfoge.data.loader;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.crfoge.data.loader.dto.AreaEffectConfigDTO;
import org.crfoge.data.loader.dto.BuffOnDamageConfigDTO;
import org.crfoge.data.loader.dto.CardConfigDTO;
import org.crfoge.data.loader.dto.DeathDamageConfigDTO;
import org.crfoge.data.loader.dto.DeathSpawnConfigDTO;
import org.crfoge.data.loader.dto.EffectConfigDTO;
import org.crfoge.data.loader.dto.LiveSpawnConfigDTO;
import org.crfoge.data.loader.dto.ProjectileConfigDTO;
import org.crfoge.data.loader.dto.UnitConfigDTO;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.effect.StatusEffectType;

public class CardLoader {

  private static final ObjectMapper mapper = createMapper();
  private static final float SPEED_BASE = 60.0f;

  private static ObjectMapper createMapper() {
    ObjectMapper om = new ObjectMapper()
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
    SimpleModule module = new SimpleModule();
    module.addDeserializer(Rarity.class, new RarityDeserializer());
    om.registerModule(module);
    return om;
  }

  private static class RarityDeserializer extends StdDeserializer<Rarity> {

    RarityDeserializer() {
      super(Rarity.class);
    }

    @Override
    public Rarity deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException {
      return Rarity.fromString(p.getText());
    }
  }

  public static List<Card> loadCards(InputStream inputStream) {
    try {
      List<CardConfigDTO> configs = mapper.readValue(inputStream, new TypeReference<>() {
      });

      // Pass 1: Build a global character name -> UnitConfigDTO lookup from all units
      Map<String, UnitConfigDTO> characterLookup = buildCharacterLookup(configs);

      // Pass 2: Convert cards with character resolution for death spawns
      return configs.stream().map(dto -> convert(dto, characterLookup)).toList();
    } catch (IOException e) {
      throw new RuntimeException("Failed to load cards from JSON", e);
    }
  }

  /**
   * Builds a lookup map from unit names to their DTO definitions across all cards.
   * Used to resolve cross-card character references in death spawns.
   */
  private static Map<String, UnitConfigDTO> buildCharacterLookup(List<CardConfigDTO> configs) {
    Map<String, UnitConfigDTO> lookup = new HashMap<>();
    for (CardConfigDTO card : configs) {
      if (card.getUnits() != null) {
        for (UnitConfigDTO unit : card.getUnits()) {
          if (unit.getName() != null) {
            lookup.put(unit.getName(), unit);
          }
        }
      }
    }
    return lookup;
  }

  private static Card convert(CardConfigDTO dto, Map<String, UnitConfigDTO> characterLookup) {
    Card.CardBuilder builder = Card.builder()
        .id(dto.getId())
        .name(dto.getName())
        .description(dto.getDescription())
        .type(dto.getType())
        .cost(dto.getCost())
        .rarity(dto.getRarity() != null ? dto.getRarity() : Rarity.UNKNOWN);

    if (dto.getProjectile() != null) {
      builder.projectile(convertProjectile(dto.getProjectile(), 0));
    }
    if (dto.getAreaEffect() != null) {
      builder.areaEffect(convertAreaEffect(dto.getAreaEffect()));
    }
    if (dto.getDeployEffect() != null) {
      builder.deployEffect(convertAreaEffect(dto.getDeployEffect()));
    }

    if (dto.getUnits() != null) {
      UnitConfigDTO primaryUnit = dto.getUnits().isEmpty() ? null : dto.getUnits().get(0);

      // Building fields: read from primary unit
      if (primaryUnit != null) {
        builder.buildingHealth(primaryUnit.getHealth());
        builder.buildingLifetime(primaryUnit.getLifeTime());

        // Spawner config from liveSpawn
        LiveSpawnConfigDTO liveSpawn = primaryUnit.getLiveSpawn();
        if (liveSpawn != null) {
          builder.spawnInterval(liveSpawn.getSpawnInterval());
          builder.spawnPauseTime(liveSpawn.getSpawnPauseTime());
          builder.spawnNumber(liveSpawn.getSpawnNumber() > 0 ? liveSpawn.getSpawnNumber() : 1);
        }

        // Death spawn count from first deathSpawn entry
        List<DeathSpawnConfigDTO> deathSpawns = primaryUnit.getDeathSpawn();
        if (deathSpawns != null && !deathSpawns.isEmpty()) {
          builder.deathSpawnCount(deathSpawns.get(0).getSpawnNumber());
        }
      }

      for (UnitConfigDTO unitDto : dto.getUnits()) {
        int count = unitDto.getCount() > 0 ? unitDto.getCount() : 1;
        TroopStats stats = convertUnit(unitDto, characterLookup);
        for (int i = 0; i < count; i++) {
          builder.troop(stats);
        }
      }
    }

    return builder.build();
  }

  private static TroopStats convertUnit(UnitConfigDTO dto,
      Map<String, UnitConfigDTO> characterLookup) {
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
        .aoeRadius(dto.getAoeRadius())
        .movementType(dto.getMovementType())
        .targetType(dto.getTargetType())
        .deployTime(dto.getDeployTime() != null ? dto.getDeployTime() : DEFAULT_DEPLOY_TIME)
        .offsetX(dto.getOffsetX())
        .offsetY(dto.getOffsetY())
        .hitEffects(convertEffects(dto.getHitEffects()))
        // Spawn Mechanics
        .spawnDamage(dto.getSpawnDamage())
        .spawnRadius(dto.getSpawnRadius())
        .spawnEffects(convertEffects(dto.getSpawnEffects()))
        // Shield
        .shieldHitpoints(dto.getShieldHitpoints())
        // Combat modifiers
        .multipleTargets(dto.getMultipleTargets())
        .multipleProjectiles(dto.getMultipleProjectiles())
        // Targeting and combat modifiers
        .targetOnlyBuildings(dto.isTargetOnlyBuildings())
        .minimumRange(dto.getMinimumRange())
        .crownTowerDamagePercent(dto.getCrownTowerDamagePercent())
        .ignorePushback(dto.isIgnorePushback());

    // Death damage
    DeathDamageConfigDTO deathDmg = dto.getDeathDamage();
    if (deathDmg != null) {
      builder.deathDamage(deathDmg.getDamage());
      builder.deathDamageRadius(deathDmg.getRadius());
    }

    // Buff on damage (e.g. EWiz stun, Mother Witch curse)
    BuffOnDamageConfigDTO buffOnDmg = dto.getBuffOnDamage();
    if (buffOnDmg != null) {
      StatusEffectType buffType = StatusEffectType.fromBuffName(buffOnDmg.getBuff());
      if (buffType != null) {
        builder.buffOnDamage(EffectStats.builder()
            .type(buffType)
            .duration(buffOnDmg.getDuration())
            .build());
      }
    }

    // Death spawns: resolve character references using the global lookup
    if (dto.getDeathSpawn() != null) {
      List<DeathSpawnEntry> deathSpawns = new ArrayList<>();
      for (DeathSpawnConfigDTO ds : dto.getDeathSpawn()) {
        UnitConfigDTO resolved = characterLookup.get(ds.getSpawnCharacter());
        if (resolved != null) {
          // Convert the referenced unit (without resolving its own death spawns to avoid
          // deep recursion; pass empty lookup for nested units)
          TroopStats spawnStats = convertUnit(resolved, Map.of());
          deathSpawns.add(new DeathSpawnEntry(spawnStats, ds.getSpawnNumber(), ds.getSpawnRadius()));
        }
      }
      builder.deathSpawns(deathSpawns);
    }

    if (dto.getProjectile() != null) {
      int damageSource = dto.getProjectile().getDamage() > 0
          ? dto.getProjectile().getDamage()
          : dto.getDamage();

      builder.projectile(convertProjectile(dto.getProjectile(), damageSource));
    }

    return builder.build();
  }

  private static ProjectileStats convertProjectile(ProjectileConfigDTO dto, int fallbackDamage) {
    if (dto == null) {
      return null;
    }

    float effectiveSpeed = dto.getSpeed() / SPEED_BASE;
    int effectiveDamage = dto.getDamage() > 0 ? dto.getDamage() : fallbackDamage;

    return ProjectileStats.builder()
        .name(dto.getName())
        .damage(effectiveDamage)
        .speed(effectiveSpeed)
        .radius(dto.getRadius())
        .homing(dto.getHoming() != null ? dto.getHoming() : true)
        .hitEffects(convertEffects(dto.getHitEffects()))
        .targetBuff(StatusEffectType.fromBuffName(dto.getTargetBuff()))
        .buffDuration(dto.getBuffDuration())
        .build();
  }

  private static AreaEffectStats convertAreaEffect(AreaEffectConfigDTO dto) {
    if (dto == null) {
      return null;
    }
    return AreaEffectStats.builder()
        .name(dto.getName())
        .radius(dto.getRadius())
        .lifeDuration(dto.getLifeDuration())
        .hitsGround(dto.isHitsGround())
        .hitsAir(dto.isHitsAir())
        .damage(dto.getDamage())
        .hitSpeed(dto.getHitSpeed())
        .buff(dto.getBuff())
        .buffDuration(dto.getBuffDuration())
        .crownTowerDamagePercent(dto.getCrownTowerDamagePercent())
        .build();
  }

  private static List<EffectStats> convertEffects(List<EffectConfigDTO> dtos) {
    if (dtos == null) {
      return new ArrayList<>();
    }
    return dtos.stream().map(dto -> {
      EffectStats.EffectStatsBuilder builder = EffectStats.builder()
          .type(dto.getType())
          .duration(dto.getDuration())
          .intensity(dto.getIntensity());

      if (dto.getSpawnUnit() != null) {
        builder.spawnSpecies(convertUnit(dto.getSpawnUnit(), Map.of()));
      }

      return builder.build();
    }).toList();
  }
}
