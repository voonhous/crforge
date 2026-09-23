package org.crforge.core.pathfinding.combat;

import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A base stat at a level.
 *
 * <p>Card stats ({@link ScalingMode#card()}) are the base times the rarity's multiplier for the
 * step, over 100, with step 0 returning the base itself. Crown tower stats are the base times a
 * multiplier compounded once per step by the percentage of that step, truncated at every step, over
 * 100. Every product is a 32-bit product and every division truncates, as the established rule has
 * them; the two are not the same as a floating-point calculation for every input.
 *
 * <p>The two getters name the mode from the entity's columns: the king tower has its own modes for
 * both stats, a princess tower its own, and everything else is a card.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: both scaling rules, the mode each getter names, and the projectile fallback of"
            + " the damage getter. Not settled: the second flag that makes a building's hit"
            + " points scale as a princess tower's, whose column has no published name and is not"
            + " carried; and the hit-points getter's caller, which also applies a percentage to"
            + " the result before it becomes a maximum.")
public final class LevelScaling {

  /** From this multiplier on, the compounding divides before it multiplies to stay in range. */
  private static final int LARGE_MULTIPLIER = 100_000;

  private LevelScaling() {
    // Utility class
  }

  /**
   * Scales a base stat.
   *
   * @param globals the tower percentages and the tournament cap
   * @param base the stat at the first level
   * @param packedLevel the entity's level, see {@link PackedLevel}
   * @param mode which rule applies
   * @param rarity the entity's rarity, or null for an entity without one, whose card stats are left
   *     at the base and whose tower stats cap at the start level itself
   */
  public static int scale(
      ScalingGlobals globals, int base, int packedLevel, ScalingMode mode, RarityTable rarity) {
    int steps = PackedLevel.steps(packedLevel);
    if (rarity != null && mode.card()) {
      if (steps == 0) {
        return base;
      }
      return base * rarity.multiplier(steps - 1) / 100;
    }
    int cap = globals.towerScalingStartExpLevel() - (rarity != null ? rarity.relativeLevel() : 0);
    ScalingGlobals.Percentages percentages = globals.percentages(mode);
    int multiplier = 100;
    for (int step = 0; step < steps; step++) {
      int percent =
          100
              + (step == cap - 1
                  ? percentages.atCap()
                  : step < cap ? percentages.perLevel() : percentages.afterCap());
      if (multiplier < LARGE_MULTIPLIER) {
        multiplier = percent * multiplier / 100;
      } else {
        multiplier = percent * Integer.divideUnsigned(multiplier, 100);
      }
    }
    return base * multiplier / 100;
  }

  /**
   * Hit points at a level: the king tower's mode for a summoner, a princess tower's for a summoner
   * tower, else the card mode.
   *
   * @param summoner the column that marks the king tower
   * @param summonerTower the column that marks a princess tower
   */
  public static int hitpoints(
      ScalingGlobals globals,
      int hitpoints,
      int packedLevel,
      RarityTable rarity,
      boolean summoner,
      boolean summonerTower) {
    ScalingMode mode =
        summoner
            ? ScalingMode.KING_HITPOINTS
            : summonerTower ? ScalingMode.TOWER_HITPOINTS : ScalingMode.CARD_HITPOINTS;
    return scale(globals, hitpoints, packedLevel, mode, rarity);
  }

  /**
   * Damage at a level: the king tower's mode for a summoner, a princess tower's for a summoner
   * tower, else the card mode. A card whose damage scales to nothing deals its projectile's damage
   * at the level instead, when it has a projectile and that damage is at least 1.
   *
   * @param summoner the column that marks the king tower
   * @param summonerTower the column that marks a princess tower
   * @param projectileDamage the projectile's damage at the level, or null for a unit without a
   *     projectile
   */
  public static int damage(
      ScalingGlobals globals,
      int damage,
      int packedLevel,
      RarityTable rarity,
      boolean summoner,
      boolean summonerTower,
      IntSupplier projectileDamage) {
    if (summoner) {
      return scale(globals, damage, packedLevel, ScalingMode.KING_DAMAGE, rarity);
    }
    if (summonerTower) {
      return scale(globals, damage, packedLevel, ScalingMode.TOWER_DAMAGE, rarity);
    }
    int value = scale(globals, damage, packedLevel, ScalingMode.CARD_DAMAGE, rarity);
    if (value > 0 || projectileDamage == null) {
      return value;
    }
    int projectile = projectileDamage.getAsInt();
    return projectile < 1 ? value : projectile;
  }
}
