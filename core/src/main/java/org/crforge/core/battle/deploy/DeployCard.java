package org.crforge.core.battle.deploy;

import java.util.List;
import org.crforge.core.battle.unit.UnitData;

/**
 * The columns of a card that its placement reads: the units a troop card summons and how many, the
 * shape and stagger of their formation, where the card may be placed, and what a spell card casts.
 *
 * <p>A troop card may summon a list of characters in place of its groups: each at an offset of its
 * own, after the groups' units in creation order. Only the Three Musketeers' does.
 *
 * <p>A spell card summons no unit: it casts a projectile from its side's king tower or an area
 * effect at the placed point. A troop card may cast a projectile too, before its units are made.
 *
 * @param name the card's name
 * @param unit the unit the card summons first, or null for a spell: its summoned character, else
 *     its list's first character
 * @param count how many of it, at least one; the first group counts it only when the card names its
 *     summoned character
 * @param secondary the unit of the card's second group, or null
 * @param secondaryCount how many of that, as the row sets it; the second group counts it only with
 *     its unit
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
 * @param projectile the projectile a spell casts from the king tower, or the one a troop card casts
 *     onto its placed point as it plays; null for none
 * @param areaEffect the area effect a spell casts at the placed point, or null
 * @param searchUnit the unit the placement is searched for in place of the card's own: a spell's
 *     projectile's spawned character, and for either the row a unit that tunnels morphs into as it
 *     surfaces; null for none
 * @param spellAsDeploy true for a spell thrown as a projectile at the placed point, which snaps to
 *     the tile centre whatever it spawns
 * @param radius a casting card's radius: the circle Arrows' ring and jitter are drawn in
 * @param multipleProjectiles how many projectiles a spell casts in one wave; 0 for one
 * @param projectileWaves how many waves; 0 for one
 * @param projectileWaveIntervalMs the time between two waves
 * @param projectileIntervalMs the time between two projectiles of a wave
 * @param listed the characters the card summons after its groups, each with its offset; empty for
 *     none
 * @param listOffsetsXMirrored whether a list's offsets across the width turn over for the top
 *     side's play on the right half of the arena
 * @param group whether the card is a group: its construction links each unit it makes into a chain
 *     after the one made before it
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
    int projectileIntervalMs,
    List<Listed> listed,
    boolean listOffsetsXMirrored,
    boolean group) {

  public DeployCard {
    listed = List.copyOf(listed);
  }

  /**
   * One character of a card's list, with its offset from the placed point as the row lists it; the
   * placement turns it by the playing side and the half of the arena.
   *
   * @param unit the character
   * @param offsetX its listed offset across the width
   * @param offsetY its listed offset along the length
   */
  public record Listed(UnitData unit, int offsetX, int offsetY) {}

  /** True for a spell card, which summons no unit and casts instead. */
  public boolean spell() {
    return unit == null;
  }

  /** True for a card that casts as it plays: a spell, or a troop card with a projectile. */
  public boolean casts() {
    return spell() || projectile != null;
  }

  /**
   * How many units the first group places: its count when the card names its summoned character,
   * none for a card that only lists its characters.
   */
  public int primaryCount() {
    return listed.isEmpty() ? count : 0;
  }

  /** How many units the second group places: its count when it has a unit, else none. */
  public int secondaryTotal() {
    return secondary == null ? 0 : secondaryCount;
  }

  /**
   * The unit of the index-th place of the card: the first group, then the second, then the list.
   */
  public UnitData unitAt(int index) {
    int listIndex = index - primaryCount() - secondaryTotal();
    if (listIndex >= 0 && listIndex < listed.size()) {
      return listed.get(listIndex).unit();
    }
    return index < count || secondary == null ? unit : secondary;
  }

  /** How many units the card places in all; none for a spell. */
  public int total() {
    return spell() ? 0 : primaryCount() + secondaryTotal() + listed.size();
  }

  /**
   * Whether the card summons a champion: its summoned character, its second group's or one of its
   * list's, as Goblinstein's doctor is its second group's.
   */
  public boolean summonsChampion() {
    return champion() != null;
  }

  /**
   * The champion the card summons, as a champion's slot finds it: its summoned character, else its
   * second group's, else the first of its list's that is a champion; null for none.
   */
  public UnitData champion() {
    if (unit != null && unit.champion()) {
      return unit;
    }
    if (secondary != null && secondary.champion()) {
      return secondary;
    }
    for (Listed entry : listed) {
      if (entry.unit().champion()) {
        return entry.unit();
      }
    }
    return null;
  }

  /**
   * The unit the placement is searched for: a spell's search unit, a troop card's own search unit
   * when it has one, else its summoned unit.
   */
  public UnitData placementUnit() {
    return spell() || searchUnit != null ? searchUnit : unit;
  }
}
