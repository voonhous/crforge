package org.crforge.core.battle.unit;

import lombok.Builder;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * The published columns of one unit or building that the battle reads, in the units the columns are
 * published in: distances in game units (1000 per tile), times in whole milliseconds, speed in game
 * units per 50 ms step.
 *
 * <p>Nothing here is converted. A speed of 60 is 60 game units per step, a hit speed of 1200 is
 * 1200 ms; the battle steps integer milliseconds, so no column ever passes through a float.
 *
 * @param name the unit's data name, which is also the identity of its configuration row
 * @param speed movement budget in game units per step; 0 for a building
 * @param range attack range
 * @param sightRange how far the unit notices targets
 * @param collisionRadius radius of the unit's collision circle
 * @param mass weight in pushes; 0 for a building
 * @param hitSpeedMs time between two hits
 * @param loadTimeMs wind-up before a hit
 * @param deployTimeMs countdown between placement and the first move
 * @param attacksGround whether the unit can hit ground targets
 * @param attacksAir whether the unit can hit air targets
 * @param air whether the unit flies
 * @param building whether the unit is a building
 * @param king whether the unit is a king tower
 * @param summonerTower whether the unit is a princess tower
 * @param hitpoints hit points at the first level
 * @param damage damage per hit at the first level
 * @param crownTowerDamagePercent difference, in percent, between what a hit deals to a crown tower
 *     and what it deals to anything else; 0 for a unit that hits both alike
 * @param rarity the rarity whose table scales the unit's stats by level: the unit's own row's,
 *     which is what the level a unit is created at is packed against
 * @param projectile the projectile the unit fires instead of hitting directly, or null for a unit
 *     that hits directly
 * @param projectileStartRadius how far along the line to the target a projectile leaves the unit
 * @param projectileStartZ how high above the unit a projectile leaves it
 * @param projectileYOffset how far along the arena's length a projectile's start is shifted; the
 *     top side's is mirrored
 * @param multipleProjectiles how many projectiles one attack launches; 0 and 1 both mean one
 * @param areaDamageRadius for a unit that hits directly, the radius of the circle each hit damages,
 *     0 for one that hits its target alone; for a unit that fires several projectiles, their spread
 * @param selfAsAoeCenter true when the circle a hit damages is centred on the unit itself rather
 *     than on its target
 * @param overrideAttackFinishTime true when the unit waits its own time, not the global one, before
 *     it takes a new target after losing one
 * @param attackFinishTimeMs that own wait
 * @param spawnRadius the radius of the formation a card places the unit in, when the card sets
 *     none; 0 for none, which falls back to the collision radius
 * @param spawnAngleShift degrees the formation is turned by
 * @param flyingHeight how high the unit flies; 0 for a ground unit
 * @param spawnPathfindSpeed the speed of a unit that walks to its placement; 0 for one placed at
 *     once
 * @param tileSizeOverride the tiles a building's footprint spans, when not derived from its
 *     collision radius; 0 for none
 * @param noDeploySizeW the width, in tiles, of the box around a building that the other side may
 *     not place in; 0 for none
 * @param noDeploySizeH the height of that box, in tiles
 */
@Builder(toBuilder = true)
public record UnitData(
    String name,
    int speed,
    int range,
    int sightRange,
    int collisionRadius,
    int mass,
    int hitSpeedMs,
    int loadTimeMs,
    int deployTimeMs,
    boolean attacksGround,
    boolean attacksAir,
    boolean air,
    boolean building,
    boolean king,
    boolean summonerTower,
    int hitpoints,
    int damage,
    int crownTowerDamagePercent,
    RarityTable rarity,
    ProjectileData projectile,
    int projectileStartRadius,
    int projectileStartZ,
    int projectileYOffset,
    int multipleProjectiles,
    int areaDamageRadius,
    boolean selfAsAoeCenter,
    boolean overrideAttackFinishTime,
    int attackFinishTimeMs,
    int spawnRadius,
    int spawnAngleShift,
    int flyingHeight,
    int spawnPathfindSpeed,
    int tileSizeOverride,
    int noDeploySizeW,
    int noDeploySizeH) {

  /**
   * The king tower's published columns. The towers carry no rarity column; Common is the rarity
   * they are scaled as.
   */
  public static final UnitData KING_TOWER =
      UnitData.builder()
          .name("KingTower")
          .range(7000)
          .sightRange(7000)
          .collisionRadius(1400)
          .hitSpeedMs(1000)
          .loadTimeMs(500)
          .attacksGround(true)
          .attacksAir(true)
          .building(true)
          .king(true)
          .tileSizeOverride(2)
          .noDeploySizeW(18)
          .noDeploySizeH(16)
          .hitpoints(2400)
          .rarity(RarityTable.COMMON)
          .projectile(ProjectileData.KING_PROJECTILE)
          .projectileStartRadius(750)
          .projectileStartZ(3500)
          .projectileYOffset(400)
          .build();

  /** The princess tower's published columns. */
  public static final UnitData PRINCESS_TOWER =
      UnitData.builder()
          .name("PrincessTower")
          .range(7500)
          .sightRange(7500)
          .collisionRadius(1000)
          .hitSpeedMs(800)
          .attacksGround(true)
          .attacksAir(true)
          .building(true)
          .summonerTower(true)
          .noDeploySizeW(11)
          .noDeploySizeH(21)
          .hitpoints(1400)
          .rarity(RarityTable.COMMON)
          .projectile(ProjectileData.TOWER_PRINCESS_PROJECTILE)
          .projectileStartRadius(300)
          .projectileStartZ(3000)
          .build();

  /** True for a unit that fires a projectile rather than hitting its target directly. */
  public boolean hasProjectile() {
    return projectile != null;
  }
}
