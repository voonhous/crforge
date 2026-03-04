package org.crforge.core.card;

/**
 * Card rarity. Determines the multiplier table used for level scaling.
 * <p>
 * Each rarity has a minimum card level in the real game:
 * Common=1, Rare=3, Epic=6, Legendary=9. Levels below the rarity's
 * minimum are clamped to the minimum by {@link LevelScaling}.
 */
public enum Rarity {
  COMMON,
  RARE,
  EPIC,
  LEGENDARY,
  CHAMPION
}
