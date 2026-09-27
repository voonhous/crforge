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
    List<String> unmodelledColumns) {

  public BuffData {
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
  }
}
