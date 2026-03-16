package org.crforge.core.ability;

import java.util.List;

/**
 * BUFF_ALLY ability data (Rune Giant / GiantBuffer). Every {@code cooldown} seconds (after an
 * initial {@code actionDelay}), targets the {@code maxTargets} closest friendly troops within
 * {@code searchRange} tiles and applies a damage buff. Buffed troops deal bonus damage on every
 * {@code attackAmount}th attack. The buff persists for {@code persistAfterDeath} seconds after the
 * source dies.
 *
 * @param addedDamage base bonus damage per proc (level-1, scaled at spawn)
 * @param addedCrownTowerDamage base bonus crown tower damage per proc (level-1, scaled at spawn)
 * @param attackAmount number of attacks between procs (e.g. 3 = every 3rd attack)
 * @param searchRange range in tiles to search for friendly targets
 * @param maxTargets maximum number of friendlies to buff per cycle
 * @param cooldown seconds between buff cycles
 * @param actionDelay initial delay before first buff cycle
 * @param buffDelay delay before buff becomes active on target (simulates projectile travel)
 * @param maxRange maximum range before buff is dropped (unused in sim, kept for data parity)
 * @param persistAfterDeath seconds the buff persists after the source GiantBuffer dies
 * @param damageMultipliers per-projectile/unit damage multiplier overrides
 */
public record BuffAllyAbility(
    int addedDamage,
    int addedCrownTowerDamage,
    int attackAmount,
    float searchRange,
    int maxTargets,
    float cooldown,
    float actionDelay,
    float buffDelay,
    float maxRange,
    float persistAfterDeath,
    List<DamageMultiplierEntry> damageMultipliers)
    implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.BUFF_ALLY;
  }
}
