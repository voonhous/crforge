package org.crforge.core.battle.unit;

import java.util.List;
import lombok.Builder;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * The published columns of one character buff row that the battle reads, in the columns' own units.
 *
 * @param name the row's name, which is also what two buffs are compared by
 * @param rarity the rarity an instance's level is packed against
 * @param speedMultiplier the movement speed percent: a boost from 1 up, a slow below 0, none at 0
 * @param hitSpeedMultiplier the attack time step percent, read the same way; -100 stops it
 * @param spawnSpeedMultiplier the spawner time step percent, read the same way
 * @param hitFrequency the time between two hits of its damage over time; 0 for none, below 0 for
 *     one hit on the first visit
 * @param damagePerSecond the damage over time at the first level, per second
 * @param crownTowerDamagePerHit a crown tower's damage per hit at the first level; 0 to use the
 *     percent
 * @param crownTowerDamagePercent how much more or less a crown tower takes, in percent
 * @param buildingDamagePercent the share of the damage a building takes, in percent; 0 for all
 * @param enableStacking true when an instance is refreshed only by the source that applied it
 * @param playerSpecificBuff true when an instance is refreshed only from its own side
 * @param noEffectToCrownTowers true when a crown tower takes nothing of it
 * @param ignoreBuildings true when a building takes nothing of it
 * @param deathSpawn the character its carrier leaves as it dies, or null
 * @param deathSpawnCount how many of it
 * @param deathSpawnRadius the ring they stand on; 0 for the point in front of the carrier
 * @param deathSpawnSameLocation true when they stand on the carrier's point itself
 * @param deathSpawnIsEnemy true when they are made for the carrier's opponent
 * @param deathSpawnDeployDelay true when they start deploying
 * @param otherBuffDeathSpawnAllowed true when it may be listed beside another buff with a death
 *     spawn that allows it too
 * @param invisible true when it makes its carrier invisible while it is listed
 * @param healPerSecond the heal over time at the first level, per second, dealt at its hit
 *     frequency
 * @param allowedOverHealPercent the share of its carrier's maximum, in percent, its heal may raise
 *     the hit points to; 0 for the maximum
 * @param unmodelledColumns the columns its row sets that the battle does not model
 */
@Builder
public record BuffData(
    String name,
    RarityTable rarity,
    int speedMultiplier,
    int hitSpeedMultiplier,
    int spawnSpeedMultiplier,
    int hitFrequency,
    int damagePerSecond,
    int crownTowerDamagePerHit,
    int crownTowerDamagePercent,
    int buildingDamagePercent,
    boolean enableStacking,
    boolean playerSpecificBuff,
    boolean noEffectToCrownTowers,
    boolean ignoreBuildings,
    String deathSpawn,
    int deathSpawnCount,
    int deathSpawnRadius,
    boolean deathSpawnSameLocation,
    boolean deathSpawnIsEnemy,
    boolean deathSpawnDeployDelay,
    boolean otherBuffDeathSpawnAllowed,
    boolean invisible,
    int healPerSecond,
    int allowedOverHealPercent,
    List<String> unmodelledColumns) {

  public BuffData {
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
  }
}
