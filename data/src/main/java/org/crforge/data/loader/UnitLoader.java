package org.crforge.data.loader;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.crforge.core.ability.AbilityData;
import org.crforge.core.ability.AbilityType;
import org.crforge.core.ability.BuffAllyAbility;
import org.crforge.core.ability.ChargeAbility;
import org.crforge.core.ability.DamageMultiplierEntry;
import org.crforge.core.ability.DashAbility;
import org.crforge.core.ability.HidingAbility;
import org.crforge.core.ability.HookAbility;
import org.crforge.core.ability.RangedAttackAbility;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.ability.StealthAbility;
import org.crforge.core.ability.TunnelAbility;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.ability.VariableDamageStage;
import org.crforge.core.card.AttackSequenceHit;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TransformationConfig;
import org.crforge.core.card.TroopStats;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.data.loader.dto.AbilityConfigDTO;
import org.crforge.data.loader.dto.BuffOnDamageConfigDTO;
import org.crforge.data.loader.dto.DeathDamageConfigDTO;
import org.crforge.data.loader.dto.DeathSpawnConfigDTO;
import org.crforge.data.loader.dto.LiveSpawnConfigDTO;
import org.crforge.data.loader.dto.TransformationConfigDTO;
import org.crforge.data.loader.dto.UnitConfigDTO;

/**
 * Loads unit definitions from units.json and converts them to TroopStats. Resolves projectile and
 * death spawn references from external maps.
 */
public class UnitLoader {

  private static final ObjectMapper mapper =
      new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
  private static final float SPEED_BASE = 60.0f;

  /**
   * Loads all unit definitions from the given input stream. Uses recursive resolution with
   * memoization to handle death spawn chains of arbitrary depth (e.g., SkeletonBalloon ->
   * SkeletonContainer -> Skeleton). Dependencies are resolved before the unit itself, so
   * convertUnit always sees a fully-resolved result map.
   *
   * @param inputStream JSON input in Map(String, UnitConfigDTO) format
   * @param projectileMap resolved projectile map for projectile reference resolution
   * @return map of unit name to TroopStats
   */
  public static Map<String, TroopStats> loadUnits(
      InputStream inputStream, Map<String, ProjectileStats> projectileMap) {
    try {
      Map<String, UnitConfigDTO> dtos = mapper.readValue(inputStream, new TypeReference<>() {});

      Map<String, TroopStats> result = new LinkedHashMap<>();
      Set<String> resolving = new HashSet<>();
      for (String name : dtos.keySet()) {
        resolveUnit(name, dtos, projectileMap, result, resolving);
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException("Failed to load units from JSON", e);
    }
  }

  /**
   * Recursively resolves a unit and all its death spawn dependencies. Uses memoization (the result
   * map) to avoid redundant work and a resolving set to detect circular chains.
   */
  private static TroopStats resolveUnit(
      String name,
      Map<String, UnitConfigDTO> dtos,
      Map<String, ProjectileStats> projectileMap,
      Map<String, TroopStats> result,
      Set<String> resolving) {
    if (result.containsKey(name)) {
      return result.get(name); // Already resolved (memoized)
    }
    UnitConfigDTO dto = dtos.get(name);
    if (dto == null) {
      return null; // Unknown reference, skip
    }
    if (!resolving.add(name)) {
      throw new IllegalStateException("Circular death spawn chain detected: " + name);
    }
    // Recursively resolve death spawn dependencies first
    if (dto.getDeathSpawn() != null) {
      for (DeathSpawnConfigDTO ds : dto.getDeathSpawn()) {
        resolveUnit(ds.getSpawnCharacter(), dtos, projectileMap, result, resolving);
      }
    }
    // Recursively resolve liveSpawn dependencies (e.g. PhoenixEgg -> PhoenixNoRespawn)
    if (dto.getLiveSpawn() != null && dto.getLiveSpawn().getSpawnCharacter() != null) {
      resolveUnit(dto.getLiveSpawn().getSpawnCharacter(), dtos, projectileMap, result, resolving);
    }
    // Recursively resolve spawnPathfindMorph target (e.g. GoblinDrillDig -> GoblinDrill)
    if (dto.getSpawnPathfindMorph() != null) {
      resolveUnit(dto.getSpawnPathfindMorph(), dtos, projectileMap, result, resolving);
    }
    // Recursively resolve transformation target (e.g. GoblinDemolisher -> kamikaze form)
    if (dto.getTransformation() != null
        && dto.getTransformation().getTransformCharacter() != null) {
      resolveUnit(
          dto.getTransformation().getTransformCharacter(), dtos, projectileMap, result, resolving);
    }
    // Recursively resolve deathSpawnProjectile's spawn character (e.g. Phoenix -> PhoenixFireball
    // -> PhoenixEgg)
    if (dto.getDeathSpawnProjectile() != null && projectileMap != null) {
      ProjectileStats deathProj = projectileMap.get(dto.getDeathSpawnProjectile());
      if (deathProj != null && deathProj.getSpawnCharacterName() != null) {
        resolveUnit(deathProj.getSpawnCharacterName(), dtos, projectileMap, result, resolving);
      }
    }
    // All dependencies now in result map; convert this unit
    TroopStats stats = convertUnit(dto, projectileMap, result);
    result.put(name, stats);
    resolving.remove(name);
    return stats;
  }

  /**
   * Converts a single UnitConfigDTO to TroopStats.
   *
   * @param dto the DTO to convert
   * @param projectileMap map for resolving projectile string references
   * @param unitMap map for resolving death spawn character references (contains resolved deps)
   * @return the converted TroopStats
   */
  static TroopStats convertUnit(
      UnitConfigDTO dto,
      Map<String, ProjectileStats> projectileMap,
      Map<String, TroopStats> unitMap) {
    // Validate required fields
    Objects.requireNonNull(
        dto.getMovementType(), "MovementType is required for unit: " + dto.getName());

    // Validation: Require TargetType if the unit has ANY attack capability
    boolean hasAttackCapability =
        dto.getDamage() > 0 || dto.getProjectile() != null || dto.getAttackSequence() != null;
    if (hasAttackCapability) {
      Objects.requireNonNull(
          dto.getTargetType(), "TargetType is required for attacking unit: " + dto.getName());
    }

    // Conversion: Base speed 60 = 1.0 tiles/sec
    float effectiveSpeed = dto.getSpeed() / SPEED_BASE;

    // Resolve radii
    float colRad = dto.getCollisionRadius() != null ? dto.getCollisionRadius() : 0.5f;
    float visRad = dto.getVisualRadius() != null ? dto.getVisualRadius() : colRad;

    TroopStats.TroopStatsBuilder builder =
        TroopStats.builder()
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
            .deployDelay(dto.getDeployDelay() != null ? dto.getDeployDelay() : 0f)
            // Shield
            .shieldHitpoints(dto.getShieldHitpoints())
            // Combat modifiers
            .multipleTargets(dto.getMultipleTargets())
            .multipleProjectiles(dto.getMultipleProjectiles())
            .selfAsAoeCenter(dto.isSelfAsAoeCenter())
            // Targeting and combat modifiers
            .targetOnlyBuildings(dto.isTargetOnlyBuildings())
            .targetOnlyTroops(dto.isTargetOnlyTroops())
            .ignoreTargetsWithBuff(dto.getIgnoreTargetsWithBuff())
            .minimumRange(dto.getMinimumRange())
            .crownTowerDamagePercent(dto.getCrownTowerDamagePercent())
            .ignorePushback(dto.isIgnorePushback())
            .kamikaze(dto.isKamikaze())
            .jumpEnabled(dto.isJumpEnabled())
            .hovering(dto.isHovering())
            .spawnPathfindSpeed(dto.getSpawnPathfindSpeed() / SPEED_BASE)
            .attackDashTime(dto.getAttackDashTime())
            .attackPushBack(dto.getAttackPushBack() / 1000f)
            // Building lifetime
            .lifeTime(dto.getLifeTime())
            // Elixir granted to opponent on death (e.g. Elixir Golem)
            .manaOnDeathForOpponent(dto.getManaOnDeathForOpponent())
            // Elixir collector fields (e.g. ElixirCollector)
            .manaOnDeath(dto.getManaOnDeath())
            .manaCollectAmount(dto.getManaCollectAmount())
            .manaGenerateTime(dto.getManaGenerateTime())
            // Buff immunity
            .ignoreBuff(dto.getIgnoreBuff() != null ? dto.getIgnoreBuff() : List.of());

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
        builder.buffOnDamage(
            EffectStats.builder()
                .type(buffType)
                .duration(buffOnDmg.getDuration())
                .buffName(buffOnDmg.getBuff())
                .build());
      }
    }

    // Death area effect (e.g. RageBarbarianBottle drops Rage zone on death)
    if (dto.getDeathAreaEffect() != null) {
      builder.deathAreaEffect(CardLoader.convertAreaEffect(dto.getDeathAreaEffect(), unitMap));
    }

    // Spawn area effect (e.g. BattleHealer heals nearby friendlies on deploy)
    if (dto.getSpawnAreaEffect() != null) {
      builder.spawnAreaEffect(CardLoader.convertAreaEffect(dto.getSpawnAreaEffect(), unitMap));
    }

    // Area effect triggered on each attack hit (e.g. BattleHealer heal on hit)
    if (dto.getAreaEffectOnHit() != null) {
      builder.areaEffectOnHit(CardLoader.convertAreaEffect(dto.getAreaEffectOnHit(), unitMap));
    }

    // Death spawns: resolve character references from the unit map
    if (dto.getDeathSpawn() != null && unitMap != null) {
      List<DeathSpawnEntry> deathSpawns = new ArrayList<>();
      for (DeathSpawnConfigDTO ds : dto.getDeathSpawn()) {
        TroopStats resolved = unitMap.get(ds.getSpawnCharacter());
        if (resolved != null) {
          deathSpawns.add(
              new DeathSpawnEntry(
                  resolved,
                  ds.getSpawnNumber(),
                  ds.getSpawnRadius(),
                  ds.getDeployTime(),
                  ds.getSpawnDelay(),
                  ds.getRelativeX(),
                  ds.getRelativeY()));
        }
      }
      builder.deathSpawns(deathSpawns);
    }

    // Resolve deathSpawnProjectile (e.g. Phoenix -> PhoenixFireball)
    if (dto.getDeathSpawnProjectile() != null && projectileMap != null) {
      ProjectileStats deathProj = projectileMap.get(dto.getDeathSpawnProjectile());
      if (deathProj != null) {
        // Also resolve the projectile's spawn character if present
        if (deathProj.getSpawnCharacterName() != null && unitMap != null) {
          TroopStats spawnChar = unitMap.get(deathProj.getSpawnCharacterName());
          if (spawnChar != null) {
            deathProj = deathProj.withSpawnCharacter(spawnChar);
          }
        }
        builder.deathSpawnProjectile(deathProj);
      }
    }

    // Live spawn configuration
    LiveSpawnConfigDTO liveSpawn = dto.getLiveSpawn();
    LiveSpawnConfig liveSpawnConfig = null;
    if (liveSpawn != null) {
      liveSpawnConfig =
          new LiveSpawnConfig(
              liveSpawn.getSpawnCharacter(),
              liveSpawn.getSpawnNumber() > 0 ? liveSpawn.getSpawnNumber() : 1,
              liveSpawn.getSpawnPauseTime(),
              liveSpawn.getSpawnInterval(),
              liveSpawn.getSpawnStartTime(),
              liveSpawn.getSpawnRadius(),
              liveSpawn.isSpawnAttach(),
              liveSpawn.getSpawnLimit(),
              liveSpawn.isDestroyAtLimit(),
              liveSpawn.isSpawnOnAggro());
      builder.liveSpawn(liveSpawnConfig);
    }

    // Resolve liveSpawn spawnTemplate (e.g. PhoenixEgg -> PhoenixNoRespawn)
    if (liveSpawnConfig != null && liveSpawnConfig.spawnCharacter() != null && unitMap != null) {
      TroopStats spawnTemplate = unitMap.get(liveSpawnConfig.spawnCharacter());
      if (spawnTemplate != null) {
        builder.spawnTemplate(spawnTemplate);
      }
    }

    // Abilities (Charge, Variable Damage, etc.)
    if (dto.getAbilities() != null && !dto.getAbilities().isEmpty()) {
      // Use the first ability (cards have at most one primary ability)
      AbilityConfigDTO abilityDto = dto.getAbilities().get(0);
      AbilityData ability = convertAbility(abilityDto, projectileMap);
      if (ability != null) {
        builder.ability(ability);
      }
    }

    // Resolve HP-threshold transformation (e.g. GoblinDemolisher -> kamikaze form)
    TransformationConfigDTO transformDto = dto.getTransformation();
    if (transformDto != null && transformDto.getTransformCharacter() != null && unitMap != null) {
      TroopStats transformStats = unitMap.get(transformDto.getTransformCharacter());
      if (transformStats != null) {
        builder.transformConfig(
            new TransformationConfig(transformStats, transformDto.getHealthPercent()));
      }
    }

    // Resolve spawnPathfindMorph target (e.g. GoblinDrillDig -> GoblinDrill)
    if (dto.getSpawnPathfindMorph() != null && unitMap != null) {
      TroopStats morphTarget = unitMap.get(dto.getSpawnPathfindMorph());
      if (morphTarget != null) {
        builder.morphTarget(morphTarget);
      }
    }

    // Auto-create TunnelAbility when spawnPathfindSpeed is set and no other ability exists
    if (dto.getSpawnPathfindSpeed() > 0
        && (dto.getAbilities() == null || dto.getAbilities().isEmpty())) {
      builder.ability(new TunnelAbility(dto.getSpawnPathfindSpeed() / SPEED_BASE));
    }

    // Attack sequence: per-hit damage values for multi-hit combo units (e.g. Berserker)
    if (dto.getAttackSequence() != null && dto.getAttackSequence().getHits() != null) {
      List<AttackSequenceHit> hits =
          dto.getAttackSequence().getHits().stream()
              .map(h -> new AttackSequenceHit(h.getDamage()))
              .toList();
      builder.attackSequence(hits);
    }

    // Stealth / Hiding abilities from stealth config
    if (dto.getStealth() != null) {
      if (dto.getStealth().isHidesWhenNotAttacking()) {
        // Tesla hiding: goes underground when no enemies nearby
        builder.ability(
            new HidingAbility(
                dto.getStealth().getHideTimeMs() / 1000f, dto.getStealth().getUpTimeMs() / 1000f));
      } else if (dto.getStealth().getNotAttackingTimeMs() > 0) {
        // Royal Ghost stealth: invisible after not attacking for a period
        builder.ability(
            new StealthAbility(
                dto.getStealth().getNotAttackingTimeMs() / 1000f,
                dto.getStealth().getHideTimeMs() / 1000f));
      } else if (dto.getStealth().getBuff() != null) {
        // Permanent stealth (SuspiciousBush): always invisible, never reveals on attack.
        // fadeTime=0 means invisible immediately; attackGracePeriod=MAX_VALUE means never reveals.
        builder.ability(new StealthAbility(0f, Float.MAX_VALUE));
      }
    }

    return builder.build();
  }

  static AbilityData convertAbility(
      AbilityConfigDTO dto, Map<String, ProjectileStats> projectileMap) {
    AbilityType type;
    try {
      type = AbilityType.valueOf(dto.getType());
    } catch (IllegalArgumentException e) {
      return null; // Unknown ability type, skip
    }

    return switch (type) {
      case CHARGE ->
          new ChargeAbility(
              dto.getDamage(), dto.getSpeedMultiplier() > 0 ? dto.getSpeedMultiplier() : 2.0f);
      case VARIABLE_DAMAGE -> {
        List<VariableDamageStage> stages =
            dto.getStages() != null
                ? dto.getStages().stream()
                    .map(s -> new VariableDamageStage(s.getDamage(), s.getTimeMs() / 1000f))
                    .toList()
                : List.of();
        yield new VariableDamageAbility(stages);
      }
      case DASH ->
          new DashAbility(
              dto.getDamage(),
              dto.getMinRange(),
              dto.getMaxRange(),
              dto.getRadius(),
              dto.getCooldown(),
              dto.getImmuneTimeMs() / 1000f,
              dto.getLandingTime(),
              dto.getConstantTime(),
              dto.getPushback());
      case HOOK ->
          new HookAbility(
              dto.getRange(),
              dto.getMinimumRange(),
              dto.getLoadTime(),
              dto.getDragBackSpeed(),
              dto.getDragSelfSpeed());
      case REFLECT ->
          new ReflectAbility(
              dto.getDamage(),
              dto.getRadius(),
              StatusEffectType.fromBuffName(dto.getBuff()),
              dto.getBuffDuration(),
              dto.getCrownTowerDamagePercent(),
              dto.getBuff());
      case TUNNEL ->
          // Tunnel ability is auto-created from spawnPathfindSpeed, not from abilities array
          null;
      case STEALTH ->
          // Stealth ability is auto-created from stealth field, not from abilities array
          null;
      case HIDING ->
          // Hiding ability is auto-created from stealth field, not from abilities array
          null;
      case BUFF_ALLY -> {
        List<DamageMultiplierEntry> multipliers =
            dto.getDamageMultipliers() != null
                ? dto.getDamageMultipliers().stream()
                    .map(m -> new DamageMultiplierEntry(m.getName(), m.getMultiplier()))
                    .toList()
                : List.of();
        yield new BuffAllyAbility(
            dto.getAddedDamage(),
            dto.getAddedCrownTowerDamage(),
            dto.getAttackAmount(),
            dto.getSearchRange(),
            dto.getMaxTargets(),
            dto.getCooldown(),
            dto.getActionDelay(),
            dto.getBuffDelay(),
            dto.getMaxRange(),
            dto.getPersistAfterDeath(),
            multipliers);
      }
      case RANGED_ATTACK -> {
        ProjectileStats projStats =
            (dto.getProjectile() != null && projectileMap != null)
                ? projectileMap.get(dto.getProjectile())
                : null;
        TargetType targetType =
            dto.getTargetType() != null ? TargetType.valueOf(dto.getTargetType()) : TargetType.ALL;
        yield new RangedAttackAbility(
            projStats,
            dto.getRange(),
            dto.getMinimumRange(),
            dto.getLoadTime(),
            dto.getAttackDelay(),
            dto.getAttackCooldown(),
            targetType);
      }
    };
  }
}
