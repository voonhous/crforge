package org.crforge.core.combat;

/**
 * Target selection algorithm used by units and area effects to choose which enemy to engage.
 *
 * <p>Ordinals 0 to 14 are significant: the community-decoded card data encodes this field as an
 * integer, so the declaration order below is the wire format. Do not reorder or insert constants
 * except at the end.
 */
public enum TargetSelectAlgorithm {
  NEAREST, // 0 - closest enemy (default)
  FARTHEST, // 1 - furthest enemy
  LOWEST_HP, // 2 - lowest absolute HP
  HIGHEST_HP, // 3 - highest absolute HP
  LOWEST_AD, // 4 - lowest attack damage
  HIGHEST_AD, // 5 - highest attack damage
  LOWEST_HP_RATIO, // 6 - lowest HP percentage
  HIGHEST_HP_RATIO, // 7 - highest HP percentage
  RANDOM, // 8 - random target
  CROWDEST, // 9 - most clustered area
  FARTHEST_IN_ABILITY_RANGE, // 10 - furthest within attack range
  HIGHEST_STAR, // 11 - highest star level (stubbed: falls back to NEAREST)
  LOWEST_STAR, // 12 - lowest star level (stubbed: falls back to NEAREST)
  HIGHEST_COST, // 13 - most expensive unit (stubbed: falls back to NEAREST)
  LOWEST_COST // 14 - cheapest unit (stubbed: falls back to NEAREST)
}
