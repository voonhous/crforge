package org.crforge.core.battle.unit;

import lombok.Builder;
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
 * @param rarity the rarity whose table scales the unit's stats by level
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
    RarityTable rarity) {

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
          .hitpoints(2400)
          .rarity(RarityTable.COMMON)
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
          .hitpoints(1400)
          .rarity(RarityTable.COMMON)
          .build();
}
