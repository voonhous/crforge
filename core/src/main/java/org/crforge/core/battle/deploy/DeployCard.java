package org.crforge.core.battle.deploy;

import org.crforge.core.battle.unit.UnitData;

/**
 * The columns of a card that its placement reads: the units a troop card summons and how many, the
 * shape and stagger of their formation, where the card may be placed, and what a spell card casts.
 *
 * <p>A spell card summons no unit: it casts a projectile from its side's king tower or an area
 * effect at the placed point.
 *
 * @param name the card's name
 * @param unit the unit the card summons first, or null for a spell
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
 * @param projectile the projectile a spell casts from the king tower, or null
 * @param areaEffect the area effect a spell casts at the placed point, or null
 * @param searchUnit the unit the placement is searched for in place of the card's own: a spell's
 *     projectile's spawned character, and for either the row a unit that tunnels morphs into as it
 *     surfaces; null for none
 * @param spellAsDeploy true for a spell thrown as a projectile at the placed point, which snaps to
 *     the tile centre whatever it spawns
 * @param radius a spell's radius: the circle Arrows' ring and jitter are drawn in
 * @param multipleProjectiles how many projectiles a spell casts in one wave; 0 for one
 * @param projectileWaves how many waves; 0 for one
 * @param projectileWaveIntervalMs the time between two waves
 * @param projectileIntervalMs the time between two projectiles of a wave
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
    int deployEndY,
    String projectile,
    String areaEffect,
    UnitData searchUnit,
    boolean spellAsDeploy,
    int radius,
    int multipleProjectiles,
    int projectileWaves,
    int projectileWaveIntervalMs,
    int projectileIntervalMs) {

  /** True for a spell card, which summons no unit and casts instead. */
  public boolean spell() {
    return unit == null;
  }

  /** The unit of the index-th place of the formation: the first group, then the second. */
  public UnitData unitAt(int index) {
    return index < count || secondary == null ? unit : secondary;
  }

  /** How many units the card places in all; none for a spell. */
  public int total() {
    return spell() ? 0 : count + (secondary == null ? 0 : secondaryCount);
  }

  /**
   * The unit the placement is searched for: a spell's search unit, a troop card's own search unit
   * when it has one, else its summoned unit.
   */
  public UnitData placementUnit() {
    return spell() || searchUnit != null ? searchUnit : unit;
  }
}
