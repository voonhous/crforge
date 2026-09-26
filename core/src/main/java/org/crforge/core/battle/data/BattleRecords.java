package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;

/**
 * The battle's records built from the game's own rows.
 *
 * <p>Units, projectiles and troop cards. Every field is the column of the same name, in the
 * column's own units - milliseconds, game units, the published speed - and nothing is converted or
 * chosen. A column the row leaves empty is 0 or false. Only three fields are not a column: a unit
 * flies when its flying height is above 0; a projectile's damage scaling rule is named by its
 * scaling mode column, the king tower's or the princess towers', and is the card rule otherwise;
 * and a rarity is the published row of that name.
 */
public final class BattleRecords {

  private static final String CHARACTERS = "characters";
  private static final String BUILDINGS = "buildings";
  private static final String PROJECTILES = "projectiles";
  private static final String SPELLS_CHARACTERS = "spells_characters";

  /** The card columns the placement does not model; a card that sets one is refused. */
  private static final List<String> UNMODELLED_CARD_COLUMNS =
      List.of(
          "SummonCharactersList",
          "SummonCharactersOffsetsX",
          "SummonCharactersOffsetsY",
          "CustomDeployTime",
          "SpellAsDeploy");

  private final GameTables tables;

  /**
   * @param tables the game tables of one data version
   */
  public BattleRecords(GameTables tables) {
    this.tables = tables;
  }

  /**
   * A unit or a building as the battle reads it, from the characters table or, for a building, the
   * buildings table.
   *
   * @param name the row's name
   */
  public UnitData unit(String name) {
    GameRow row = unitRow(name);
    return UnitData.builder()
        .name(row.name())
        .speed(row.intValue("Speed"))
        .range(row.intValue("Range"))
        .sightRange(row.intValue("SightRange"))
        .collisionRadius(row.intValue("CollisionRadius"))
        .mass(row.intValue("Mass"))
        .hitSpeedMs(row.intValue("HitSpeed"))
        .loadTimeMs(row.intValue("LoadTime"))
        .deployTimeMs(row.intValue("DeployTime"))
        .attacksGround(row.bool("AttacksGround"))
        .attacksAir(row.bool("AttacksAir"))
        .air(row.intValue("FlyingHeight") > 0)
        .building(row.bool("IsBuilding"))
        .king(row.bool("IsSummoner"))
        .summonerTower(row.bool("IsSummonerTower"))
        .hitpoints(row.intValue("Hitpoints"))
        .damage(row.intValue("Damage"))
        .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
        .rarity(rarity(row.string("Rarity")))
        .projectile(
            row.string("Projectile").isEmpty() ? null : projectile(row.string("Projectile")))
        .projectileStartRadius(row.intValue("ProjectileStartRadius"))
        .projectileStartZ(row.intValue("ProjectileStartZ"))
        .projectileYOffset(row.intValue("ProjectileYOffset"))
        .multipleProjectiles(row.intValue("MultipleProjectiles"))
        .areaDamageRadius(row.intValue("AreaDamageRadius"))
        .selfAsAoeCenter(row.bool("SelfAsAoeCenter"))
        .overrideAttackFinishTime(row.bool("OverrideAttackFinishTime"))
        .attackFinishTimeMs(row.intValue("AttackFinishTime"))
        .spawnRadius(row.intValue("SpawnRadius"))
        .spawnAngleShift(row.intValue("SpawnAngleShift"))
        .flyingHeight(row.intValue("FlyingHeight"))
        .spawnPathfindSpeed(row.intValue("SpawnPathfindSpeed"))
        .tileSizeOverride(row.intValue("TileSizeOverride"))
        .noDeploySizeW(row.intValue("NoDeploySizeW"))
        .noDeploySizeH(row.intValue("NoDeploySizeH"))
        .attackPushBack(row.intValue("AttackPushBack"))
        .ignorePushback(row.bool("IgnorePushback"))
        .onStartingAction(actionName(row, "OnStartingAction"))
        .onDeathAction(actionName(row, "OnDeathAction"))
        .onKilledAction(actionName(row, "OnKilledAction"))
        .build();
  }

  /**
   * A projectile as the battle reads it, from the projectiles table.
   *
   * @param name the row's name
   */
  public ProjectileData projectile(String name) {
    GameTable table = tables.table(PROJECTILES);
    checkArgument(table.has(name), () -> "the game tables have no projectile " + name);
    GameRow row = table.row(name);
    return ProjectileData.builder()
        .name(row.name())
        .rarity(rarity(row.string("Rarity")))
        .speed(row.intValue("Speed"))
        .gravity(row.intValue("Gravity"))
        .homing(row.bool("Homing"))
        .homingTimeMs(row.intValue("HomingTime"))
        .homingMinDistance(row.intValue("HomingMinDistance"))
        .damage(row.intValue("Damage"))
        .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
        .damageMode(damageMode(row.string("DamageScalingMode")))
        .radius(row.intValue("Radius"))
        .aoeToAir(row.bool("AoeToAir"))
        .aoeToGround(row.bool("AoeToGround"))
        .onlyEnemies(row.bool("OnlyEnemies"))
        .projectileRadius(row.intValue("ProjectileRadius"))
        .projectileRange(row.intValue("ProjectileRange"))
        .checkCollisions(row.bool("CheckCollisions"))
        .minDistance(row.intValue("MinDistance"))
        .circleScatter("Circle".equals(row.string("Scatter")))
        .build();
  }

  /**
   * A troop card's placement as the battle reads it, from the spells characters table: the units it
   * summons, built from their own rows, how many, its formation and where it may be placed.
   *
   * <p>A card with no count summons one. The level index a card may carry is not read, as the game
   * never reads it: the summoned units take the level the card is played at. A card that summons a
   * list of characters, places them at offsets of its own, has a deploy time of its own or deploys
   * as a spell is refused, naming the column: the placement does not model those.
   *
   * @param name the card row's name
   */
  public DeployCard card(String name) {
    GameTable table = tables.table(SPELLS_CHARACTERS);
    checkArgument(table.has(name), () -> "the game tables have no troop card " + name);
    GameRow row = table.row(name);
    for (String column : UNMODELLED_CARD_COLUMNS) {
      if (set(row, column)) {
        throw new UnsupportedOperationException(
            name + " sets " + column + ", which the card placement does not model");
      }
    }
    checkArgument(!row.string("SummonCharacter").isEmpty(), () -> name + " summons no character");
    String second = row.string("SummonCharacterSecond");
    return new DeployCard(
        row.name(),
        unit(row.string("SummonCharacter")),
        Math.max(row.intValue("SummonNumber"), 1),
        second.isEmpty() ? null : unit(second),
        second.isEmpty() ? 0 : row.intValue("SummonCharacterSecondCount"),
        row.intValue("SummonRadius"),
        row.intValue("SummonWidth"),
        row.intValue("SummonDeployDelay"),
        row.intValue("SummonDeployDelaySecond"),
        row.bool("CanDeployOnEnemySide"),
        row.bool("CanPlaceOnBuildings"),
        row.bool("CanPlaceOnWater"),
        row.bool("FullLaneDeploy"),
        row.bool("TouchdownLimitedDeploy"),
        row.intValue("DeployWTileMargin"),
        row.intValue("DeployStartY"),
        row.intValue("DeployEndY"));
  }

  /** True when a row sets a column: a value that is not empty, false, 0 or an empty list. */
  private static boolean set(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isTextual()) {
      return !value.asText().isEmpty();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.asInt() != 0;
    }
    return !value.isEmpty();
  }

  /**
   * The action row a hook column names, as a reference or by its name; null for none. A hook
   * written as an inline row of its own, with no name to build it by, is refused, so it is never
   * read as no hook at all.
   */
  private static String actionName(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isObject() && !value.has("action")) {
      throw new UnsupportedOperationException(
          row.name()
              + " writes its "
              + column
              + " inline, as "
              + value.path("ClassType").asText("an unnamed row")
              + ", which is not modelled");
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  /** The row of a unit: the characters table's, else the buildings table's. */
  private GameRow unitRow(String name) {
    for (String table : new String[] {CHARACTERS, BUILDINGS}) {
      GameTable rows = tables.table(table);
      if (rows.has(name)) {
        return rows.row(name);
      }
    }
    throw new IllegalArgumentException("the game tables have no unit " + name);
  }

  /** The published rarity row of that name; a row without a rarity is scaled as Common. */
  private static RarityTable rarity(String name) {
    if (name.isEmpty()) {
      return RarityTable.COMMON;
    }
    for (RarityTable rarity : RarityTable.PUBLISHED) {
      if (rarity.name().equals(name)) {
        return rarity;
      }
    }
    throw new IllegalArgumentException("no published rarity " + name);
  }

  /** The scaling rule a damage scaling mode names: the two tower rules, else the card rule. */
  private static ScalingMode damageMode(String mode) {
    return switch (mode) {
      case "KingTower" -> ScalingMode.KING_DAMAGE;
      case "PrincessTower" -> ScalingMode.TOWER_DAMAGE;
      default -> ScalingMode.CARD_DAMAGE;
    };
  }
}
