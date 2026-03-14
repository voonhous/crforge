package org.crforge.data.loader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.data.loader.dto.AreaEffectConfigDTO;
import org.crforge.data.loader.dto.CardConfigDTO;

/**
 * Loads card definitions from cards.json (slim format) and resolves unit/projectile references from
 * pre-loaded maps.
 */
public class CardLoader {

  private static final ObjectMapper mapper = createMapper();

  private static ObjectMapper createMapper() {
    ObjectMapper om =
        new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
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
    public Rarity deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      return Rarity.fromString(p.getText());
    }
  }

  /**
   * Loads cards from the given input stream, resolving unit and projectile references from the
   * provided maps.
   *
   * @param inputStream JSON array of CardConfigDTO in slim format
   * @param unitMap pre-loaded unit name -> TroopStats map
   * @param projectileMap pre-loaded projectile name -> ProjectileStats map
   * @return list of fully resolved Card objects
   */
  public static List<Card> loadCards(
      InputStream inputStream,
      Map<String, TroopStats> unitMap,
      Map<String, ProjectileStats> projectileMap) {
    try {
      List<CardConfigDTO> configs = mapper.readValue(inputStream, new TypeReference<>() {});
      return configs.stream().map(dto -> convert(dto, unitMap, projectileMap)).toList();
    } catch (IOException e) {
      throw new RuntimeException("Failed to load cards from JSON", e);
    }
  }

  private static Card convert(
      CardConfigDTO dto,
      Map<String, TroopStats> unitMap,
      Map<String, ProjectileStats> projectileMap) {
    Card.CardBuilder builder =
        Card.builder()
            .id(dto.getId())
            .name(dto.getName())
            .description(dto.getDescription())
            .type(dto.getType())
            .cost(dto.getCost())
            .rarity(dto.getRarity() != null ? dto.getRarity() : Rarity.UNKNOWN);

    // Resolve unit reference
    TroopStats unitStats = null;
    if (dto.getUnit() != null) {
      unitStats = unitMap.get(dto.getUnit());
      if (unitStats != null) {
        builder.unitStats(unitStats);
      }
    }
    builder.unitCount(dto.getCount() > 0 ? dto.getCount() : 1);

    // Resolve projectile reference (for spells)
    if (dto.getProjectile() != null) {
      ProjectileStats proj = projectileMap.get(dto.getProjectile());
      if (proj != null) {
        // Resolve spawnCharacter reference from unitMap (projectiles load before units,
        // so spawnCharacter stays null until resolved here)
        if (proj.getSpawnCharacterName() != null) {
          TroopStats spawnChar = unitMap.get(proj.getSpawnCharacterName());
          if (spawnChar != null) {
            proj = proj.withSpawnCharacter(spawnChar);
          }
        }
        // Also resolve spawnCharacter on sub-projectile (e.g. BarbLogProjectileRolling ->
        // Barbarian)
        if (proj.getSpawnProjectile() != null
            && proj.getSpawnProjectile().getSpawnCharacterName() != null) {
          TroopStats subSpawnChar = unitMap.get(proj.getSpawnProjectile().getSpawnCharacterName());
          if (subSpawnChar != null) {
            proj =
                proj.withSpawnProjectile(
                    proj.getSpawnProjectile().withSpawnCharacter(subSpawnChar));
          }
        }
        builder.projectile(proj);
      }
    }

    // Area effects (inline, unchanged)
    if (dto.getAreaEffect() != null) {
      builder.areaEffect(convertAreaEffect(dto.getAreaEffect()));
    }
    if (dto.getDeployEffect() != null) {
      builder.deployEffect(convertAreaEffect(dto.getDeployEffect()));
    }

    // Promote unit-level spawnAreaEffect to card deployEffect if no explicit deployEffect set
    if (dto.getDeployEffect() == null
        && unitStats != null
        && unitStats.getSpawnAreaEffect() != null) {
      builder.deployEffect(unitStats.getSpawnAreaEffect());
    }

    // Resolve summonCharacter for spells (e.g. Rage -> RageBottle)
    if (dto.getSummonCharacter() != null) {
      TroopStats summonTemplate = unitMap.get(dto.getSummonCharacter());
      if (summonTemplate != null) {
        builder.summonTemplate(summonTemplate);
      }
    }

    // summonRadius for troop deploy formation
    builder.summonRadius(dto.getSummonRadius());

    // Formation offsets (pre-computed tile-unit positions)
    if (dto.getFormationOffsets() != null && !dto.getFormationOffsets().isEmpty()) {
      List<float[]> offsets =
          dto.getFormationOffsets().stream()
              .map(pair -> new float[] {pair.get(0), pair.get(1)})
              .toList();
      builder.formationOffsets(offsets);
    }

    // Secondary unit for dual-unit cards (e.g., GoblinGang, Rascals)
    if (dto.getSecondaryUnit() != null) {
      TroopStats secondaryStats = unitMap.get(dto.getSecondaryUnit());
      if (secondaryStats != null) {
        builder.secondaryUnitStats(secondaryStats);
      }
    }
    builder.secondaryUnitCount(dto.getSecondaryCount());

    // Resolve spawn projectile for deploy-time damage (e.g. MegaKnight landing)
    if (dto.getSpawnProjectile() != null) {
      ProjectileStats spawnProj = projectileMap.get(dto.getSpawnProjectile());
      if (spawnProj != null) {
        builder.spawnProjectile(spawnProj);
      }
    }

    // Can deploy on enemy side (e.g. Miner, GoblinDrill)
    builder.canDeployOnEnemySide(dto.isCanDeployOnEnemySide());

    // Spell deploys at placement location (e.g. Log)
    builder.spellAsDeploy(dto.isSpellAsDeploy());

    // Spell wave configuration (e.g. Arrows)
    builder.spellRadius(dto.getRadius());
    builder.multipleProjectiles(dto.getMultipleProjectiles());
    builder.projectileWaves(dto.getProjectileWaves());
    builder.projectileWaveInterval(dto.getProjectileWaveInterval());

    // Resolve spawn template from unitStats.liveSpawn if present
    if (unitStats != null && unitStats.getLiveSpawn() != null) {
      LiveSpawnConfig liveSpawn = unitStats.getLiveSpawn();
      if (liveSpawn.spawnCharacter() != null) {
        TroopStats spawnTemplate = unitMap.get(liveSpawn.spawnCharacter());
        if (spawnTemplate != null) {
          builder.spawnTemplate(spawnTemplate);
        }
      }
    }

    return builder.build();
  }

  static AreaEffectStats convertAreaEffect(AreaEffectConfigDTO dto) {
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
        .hitBiggestTargets(dto.isHitBiggestTargets())
        .controlsBuff(dto.isControlsBuff())
        .build();
  }
}
