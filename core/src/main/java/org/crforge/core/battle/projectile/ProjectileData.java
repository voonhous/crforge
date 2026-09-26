package org.crforge.core.battle.projectile;

import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;

/**
 * The published columns of one projectile row that the battle reads, unconverted: distances in game
 * units (1000 per tile), times in whole milliseconds, speed in game units per 50 ms step.
 *
 * @param name the row's name, which is also the identity of the projectile's configuration
 * @param rarity the row's own rarity; the launcher's level is re-based on it when the projectile is
 *     launched, and the damage is scaled at that level
 * @param speed game units flown per step
 * @param gravity the parabola the flight's height follows; 0 for a straight flight
 * @param homing true when the projectile follows its target: the aim is re-pinned onto the target
 *     every step, and a target that leaves the battle leaves the aim where it last stood
 * @param homingTimeMs how long a projectile that homes for a limited time keeps re-aiming; 0 for
 *     one that never does
 * @param homingMinDistance the least distance from the start to the target for that limited homing
 *     to be taken up at all
 * @param damage damage at the first level
 * @param crownTowerDamagePercent difference, in percent, between what the projectile deals to a
 *     crown tower and to anything else; 0 for one that hits both alike
 * @param damageMode which scaling rule the damage follows: an ordinary row scales as a card, a few
 *     tower rows as a king or princess tower
 * @param radius the radius of the area the impact damages; 0 for a projectile that hits its one
 *     target
 * @param aoeToAir whether the area the impact damages reaches air units
 * @param aoeToGround whether the area the impact damages reaches ground units and buildings
 * @param onlyEnemies true when the area spares the launcher's own side; without it the launcher's
 *     own units in the area are damaged too
 * @param projectileRadius the radius of the flying body of a projectile that hits what it passes; 0
 *     for one that only hits at its aim
 * @param projectileRange how far a projectile without a target flies from its launcher; 0 for one
 *     that flies to its target
 * @param checkCollisions true when the projectile stops at the first entity its body touches
 * @param minDistance the least distance from the start the aim is pushed out to; 0 for none
 * @param circleScatter true for a row whose scatter pattern is the circle, which counts it among
 *     the projectiles that fly to a point rather than to a target
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the columns carried and the homing-like test that tells a projectile flying to a"
            + " point from one flying to a target. Not carried yet: the constant height, the far"
            + " distance clamp, the random angle and distance, the delays, the pingpong and drag"
            + " columns, the deflect behaviour, the chained hit and the on-impact spawns.")
@Builder(toBuilder = true)
public record ProjectileData(
    String name,
    RarityTable rarity,
    int speed,
    int gravity,
    boolean homing,
    int homingTimeMs,
    int homingMinDistance,
    int damage,
    int crownTowerDamagePercent,
    ScalingMode damageMode,
    int radius,
    boolean aoeToAir,
    boolean aoeToGround,
    boolean onlyEnemies,
    int projectileRadius,
    int projectileRange,
    boolean checkCollisions,
    int minDistance,
    boolean circleScatter) {

  public ProjectileData {
    if (damageMode == null) {
      damageMode = ScalingMode.CARD_DAMAGE;
    }
  }

  /**
   * True for a projectile that flies to a point and hits what it passes on the way, rather than to
   * a target: one with a flying body that either has a range without homing, stops at collisions or
   * scatters in a circle. Such a projectile keeps its start height as its aim height.
   */
  public boolean homingLike() {
    if (projectileRadius < 1) {
      return false;
    }
    if (!homing && projectileRange > 0) {
      return true;
    }
    return checkCollisions || circleScatter;
  }
}
