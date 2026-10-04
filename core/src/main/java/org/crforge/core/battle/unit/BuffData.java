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
 * @param hitTickFromSource true when its hits of damage over time follow the clock of the area
 *     effect that applied it rather than each instance's own count
 * @param attractPercentage the pull toward an area effect's centre, in hundredths of a percent of
 *     the carrier's configured speed, that each hit of the area effect adds; 0 for none
 * @param lateralPushPercentage the pull across that direction, in the same units; 0 for none
 * @param pushMassFactor how much the carrier's mass weakens the pull; 0 for not at all
 * @param pushSpeedFactor the percent the pull scales the carrier's configured speed by; 0 to take
 *     the percentages as they are
 * @param attractMinAngle the smallest angle between a moving area effect's travel and the way to
 *     its centre at which it pulls
 * @param attractMaxAngle the largest such angle; below 1 the angle is not tested
 * @param controlledByParent true when the area effect that applies it is also its parent, whose
 *     removal removes it
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
 * @param notCloned true when a clone of its carrier would not take a copy of it; a clone of a
 *     carrier is refused
 * @param healPerSecond the heal over time at the first level, per second, dealt at its hit
 *     frequency
 * @param allowedOverHealPercent the share of its carrier's maximum, in percent, its heal may raise
 *     the hit points to; 0 for the maximum
 * @param lockTarget true when, while it is listed, its carrier's selector keeps the reference the
 *     carrier holds
 * @param addAsIndividualBuff true when every application lists a new instance, refreshing none
 * @param aliveIfTrue the expression that keeps an instance listed while it answers other than 0,
 *     asked of the carrier on each visit; null for none
 * @param damageReduction the percent every amount that reaches its carrier's hit points loses while
 *     it is listed; below 0 the amount grows instead; 0 for none
 * @param ignorePushBack true when its carrier is not pushed back while it is listed
 * @param cloneBuff true for a Clone buff, which a parent keeps from its riders
 * @param attachedInheritAs the buff row a parent hands its riders in place of this one, or null to
 *     hand them this one
 * @param gameTagsToSet the tags its row sets, in its carrier's tag word from the carrier's next
 *     pre-hook for as long as it is listed
 * @param overrideChargeRange the charge range it gives a carrier whose row has none, while it is
 *     the first listed instance that sets one; 0 for none
 * @param onStartAction the action a newly listed instance schedules on its carrier, the carrier its
 *     cause; a refresh schedules nothing; null for none
 * @param onRemoveAction the action every removal of an instance but a death's schedules on its
 *     carrier, the carrier its cause; null for none
 * @param spawnObject the character an instance's spawner makes in front of its carrier, or null for
 *     none
 * @param spawnStartTimeMs the spawner's timer as an instance is listed, in milliseconds
 * @param spawnIntervalMs the time between two firings within a wave; below 1 the spawner never
 *     fires
 * @param spawnLimit how many firings an instance's spawner makes; below 1 it makes none
 * @param spawnNumber how many firings make a wave, after which the pause follows
 * @param spawnPauseTimeMs the time after a wave, in milliseconds
 * @param spawnerAliveRequired true when a child joins the battle only while its carrier is still
 *     listed and not removable as the cleanup folds it in
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
    boolean hitTickFromSource,
    int attractPercentage,
    int lateralPushPercentage,
    int pushMassFactor,
    int pushSpeedFactor,
    int attractMinAngle,
    int attractMaxAngle,
    boolean controlledByParent,
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
    boolean notCloned,
    int healPerSecond,
    int allowedOverHealPercent,
    boolean lockTarget,
    boolean addAsIndividualBuff,
    String aliveIfTrue,
    int damageReduction,
    boolean ignorePushBack,
    boolean cloneBuff,
    String attachedInheritAs,
    long gameTagsToSet,
    int overrideChargeRange,
    String onStartAction,
    String onRemoveAction,
    String spawnObject,
    int spawnStartTimeMs,
    int spawnIntervalMs,
    int spawnLimit,
    int spawnNumber,
    int spawnPauseTimeMs,
    boolean spawnerAliveRequired,
    List<String> unmodelledColumns) {

  public BuffData {
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
  }

  /** Whether it pulls: an area effect applying it pulls with each hit, before applying it. */
  public boolean attracts() {
    return attractPercentage != 0 || lateralPushPercentage != 0;
  }
}
