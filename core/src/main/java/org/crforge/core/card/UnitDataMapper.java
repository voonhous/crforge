package org.crforge.core.card;

import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;

/**
 * Turns the card library's unit and projectile stats into the raw columns the battle reads.
 *
 * <p>The card library stores durations as float seconds and the battle reads whole milliseconds.
 * Every published duration is a whole number of milliseconds, so rounding the product recovers the
 * column exactly. The speed columns are carried through untouched as the raw speeds.
 *
 * <p>A unit is scaled by its own row's rarity, which is what the level it is created at is packed
 * against; the level a card is played at is counted against the card's rarity, and re-basing it on
 * the row's gives the steps above the first level that the row's stats are published at. Only when
 * the row carries no rarity does the deploying card's stand in.
 */
public final class UnitDataMapper {

  private UnitDataMapper() {}

  /**
   * The battle's view of the unit a card deploys, scaled by the unit's own rarity, or by the card's
   * when its row carries none.
   */
  public static UnitData toUnitData(Card card) {
    TroopStats stats = card.getUnitStats();
    Rarity rarity = stats.getRarity() != Rarity.UNKNOWN ? stats.getRarity() : card.getRarity();
    return toUnitData(stats, rarityTable(rarity));
  }

  /**
   * The battle's view of a troop card's placement: the units it summons, its formation and where it
   * may be placed. The card's rows publish the stagger in seconds; the battle reads milliseconds.
   */
  public static DeployCard toDeployCard(Card card) {
    TroopStats second = card.getSecondaryUnitStats();
    UnitData secondary = null;
    if (second != null) {
      Rarity rarity = second.getRarity() != Rarity.UNKNOWN ? second.getRarity() : card.getRarity();
      secondary = toUnitData(second, rarityTable(rarity));
    }
    return new DeployCard(
        card.getName(),
        toUnitData(card),
        Math.max(card.getUnitCount(), 1),
        secondary,
        second == null ? 0 : card.getSecondaryUnitCount(),
        Math.round(card.getSummonRadius()),
        card.getSummonWidth(),
        Math.round(card.getSummonDeployDelay() * 1000),
        card.getSummonDeployDelaySecondMs(),
        card.isCanDeployOnEnemySide(),
        card.isCanPlaceOnBuildings(),
        card.isCanPlaceOnWater(),
        card.isFullLaneDeploy(),
        card.isTouchdownLimitedDeploy(),
        card.getDeployWTileMargin(),
        // No row of the data limits the rows a card may be placed in.
        0,
        0);
  }

  /** The battle's view of one unit of the card library, scaled by the given rarity. */
  public static UnitData toUnitData(TroopStats stats, RarityTable rarity) {
    TargetType targets = stats.getTargetType();
    return UnitData.builder()
        .name(stats.getName())
        .speed(stats.getRawSpeed())
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .collisionRadius(stats.getCollisionRadius())
        .mass(Math.round(stats.getMass()))
        .hitSpeedMs(toMs(stats.getAttackCooldown()))
        .loadTimeMs(toMs(stats.getLoadTime()))
        .deployTimeMs(toMs(stats.getDeployTime()))
        .attacksGround(targets == TargetType.GROUND || targets == TargetType.ALL)
        .attacksAir(targets == TargetType.AIR || targets == TargetType.ALL)
        .air(stats.getMovementType() == MovementType.AIR)
        .building(stats.getMovementType() == MovementType.BUILDING)
        .hitpoints(stats.getHealth())
        .damage(stats.getDamage())
        .crownTowerDamagePercent(stats.getCrownTowerDamagePercent())
        .rarity(rarity)
        .projectile(toProjectileData(stats.getProjectile()))
        .projectileStartRadius(stats.getProjectileStartRadius())
        .projectileStartZ(stats.getProjectileStartZ())
        .projectileYOffset(stats.getProjectileYOffset())
        .multipleProjectiles(stats.getMultipleProjectiles())
        .areaDamageRadius(stats.getAoeRadius())
        .selfAsAoeCenter(stats.isSelfAsAoeCenter())
        .overrideAttackFinishTime(stats.isOverrideAttackFinishTime())
        .attackFinishTimeMs(stats.getAttackFinishTime())
        .spawnRadius(stats.getSpawnRadius())
        .spawnAngleShift(stats.getSpawnAngleShift())
        .flyingHeight(stats.getFlyingHeight())
        .spawnPathfindSpeed(Math.round(stats.getSpawnPathfindSpeed()))
        .attackPushBack(stats.getAttackPushBack())
        .ignorePushback(stats.isIgnorePushback())
        .build();
  }

  /**
   * The battle's view of one projectile of the card library, or null for none. A projectile row
   * without a rarity is scaled as a Common one.
   */
  public static ProjectileData toProjectileData(ProjectileStats stats) {
    if (stats == null) {
      return null;
    }
    return ProjectileData.builder()
        .name(stats.getName())
        .rarity(rarityTable(stats.getRarity()))
        .speed(stats.getRawSpeed())
        .gravity(stats.getGravity())
        .homing(stats.isHoming())
        .homingTimeMs(stats.getHomingTime())
        .homingMinDistance(stats.getHomingMinDistance())
        .damage(stats.getDamage())
        .crownTowerDamagePercent(stats.getCrownTowerDamagePercent())
        .damageMode(damageMode(stats.getDamageScalingMode()))
        .radius(stats.getRadius())
        .aoeToAir(stats.isAoeToAir())
        .aoeToGround(stats.isAoeToGround())
        .onlyEnemies(stats.isOnlyEnemies())
        .projectileRadius(stats.getProjectileRadius())
        .projectileRange(stats.getProjectileRange())
        .checkCollisions(stats.isCheckCollisions())
        .minDistance(stats.getMinDistance())
        .circleScatter("Circle".equals(stats.getScatter()))
        .build();
  }

  /**
   * The published scaling row of a card rarity. A card without a rarity is scaled as a Common one,
   * which is how the card library scales it too.
   */
  public static RarityTable rarityTable(Rarity rarity) {
    return switch (rarity) {
      case COMMON, UNKNOWN -> RarityTable.COMMON;
      case RARE -> RarityTable.RARE;
      case EPIC -> RarityTable.EPIC;
      case LEGENDARY -> RarityTable.LEGENDARY;
      case CHAMPION -> RarityTable.CHAMPION;
    };
  }

  /**
   * The scaling rule a projectile's damage column names: the king tower's or the princess towers'
   * for the two tower modes, the card rule for everything else.
   */
  static ScalingMode damageMode(String damageScalingMode) {
    if ("KingTower".equals(damageScalingMode)) {
      return ScalingMode.KING_DAMAGE;
    }
    if ("PrincessTower".equals(damageScalingMode)) {
      return ScalingMode.TOWER_DAMAGE;
    }
    return ScalingMode.CARD_DAMAGE;
  }

  private static int toMs(float seconds) {
    return Math.round(seconds * 1000f);
  }
}
