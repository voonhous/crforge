package org.crforge.core.card;

/**
 * Card rarity. Determines the multiplier table used for level scaling.
 *
 * <p>Each rarity has a minimum card level in the real game: Common=1, Rare=3, Epic=6, Legendary=9.
 * Levels below the rarity's minimum are clamped to the minimum by {@link LevelScaling}.
 */
public enum Rarity {
  COMMON,
  RARE,
  EPIC,
  LEGENDARY,
  CHAMPION,

  /**
   * Fallback for cards with missing or empty rarity (e.g. internal sub-buildings like
   * giantskeletonbomb). Scaled the same as {@link #COMMON}.
   */
  UNKNOWN;

  /**
   * Parses a rarity string, returning {@link #UNKNOWN} for null or empty values.
   *
   * @param value the rarity name (case-insensitive), or null/empty
   * @return the matching Rarity, or UNKNOWN if the input is null/empty
   */
  public static Rarity fromString(String value) {
    if (value == null || value.isEmpty()) {
      return UNKNOWN;
    }
    return valueOf(value.toUpperCase());
  }
}
