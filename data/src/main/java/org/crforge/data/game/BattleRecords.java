package org.crforge.data.game;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;

/**
 * The battle's records built from the game's own rows.
 *
 * <p>Every field is the column of the same name, in the column's own units - milliseconds, game
 * units, the published speed - and nothing is converted or chosen. A column the row leaves empty is
 * 0 or false. Only three fields are not a column: a unit flies when its flying height is above 0; a
 * projectile's damage scaling rule is named by its scaling mode column, the king tower's or the
 * princess towers', and is the card rule otherwise; and a rarity is the published row of that name.
 */
public final class BattleRecords {

  private static final String CHARACTERS = "characters";
  private static final String BUILDINGS = "buildings";
  private static final String PROJECTILES = "projectiles";

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
