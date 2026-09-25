package org.crforge.core.battle.deploy;

import org.crforge.core.battle.unit.UnitData;

/**
 * The columns of a troop card that its placement reads: the units it summons and how many, the
 * shape and stagger of their formation, and where the card may be placed.
 *
 * @param name the card's name
 * @param unit the unit the card summons first
 * @param count how many of it
 * @param secondary the unit of the card's second group, or null
 * @param secondaryCount how many of that
 * @param summonRadius the formation's radius; 0 falls back to the unit's own
 * @param summonWidth the width of a line formation; 0 for the ring
 * @param summonDeployDelayMs the stagger between the first group's units
 * @param summonDeployDelaySecondMs the stagger between the second group's units
 * @param canDeployOnEnemySide whether the card may be placed in the other side's half
 * @param canPlaceOnBuildings whether the card may be placed where the map is not placeable
 * @param canPlaceOnWater whether the card may be placed on water
 * @param fullLaneDeploy whether only the rows open across the whole width stay open
 * @param touchdownLimitedDeploy whether the card's placement is limited in a touchdown mode
 * @param deployWTileMargin tiles kept closed at each side of the width
 * @param deployStartY the first open row, with {@code deployEndY}; both 0 for no limit
 * @param deployEndY the row from which the rows close again
 */
public record DeployCard(
    String name,
    UnitData unit,
    int count,
    UnitData secondary,
    int secondaryCount,
    int summonRadius,
    int summonWidth,
    int summonDeployDelayMs,
    int summonDeployDelaySecondMs,
    boolean canDeployOnEnemySide,
    boolean canPlaceOnBuildings,
    boolean canPlaceOnWater,
    boolean fullLaneDeploy,
    boolean touchdownLimitedDeploy,
    int deployWTileMargin,
    int deployStartY,
    int deployEndY) {

  /** The unit of the index-th place of the formation: the first group, then the second. */
  public UnitData unitAt(int index) {
    return index < count || secondary == null ? unit : secondary;
  }

  /** How many units the card places in all. */
  public int total() {
    return count + (secondary == null ? 0 : secondaryCount);
  }
}
